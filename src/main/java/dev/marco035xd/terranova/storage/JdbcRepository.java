package dev.marco035xd.terranova.storage;

import java.io.File;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import org.bukkit.configuration.ConfigurationSection;

/** Single ordered JDBC worker, optimistic revisions, durable tokens and transfer outbox. */
public final class JdbcRepository implements AutoCloseable {
  public record TokenMove(UUID token, String from, String to) {}

  public record Delivery(UUID id, UUID player, String payload) {}

  public record Stored(UUID id, long revision, String payload) {}

  public record MoneyDelivery(
      UUID id, UUID player, String currency, double amount, String status) {}

  private final ExecutorService worker =
      Executors.newSingleThreadExecutor(
          r -> {
            Thread t = new Thread(r, "Terranova-JDBC");
            t.setDaemon(false);
            return t;
          });
  private Connection connection;
  private volatile boolean closed;

  public JdbcRepository(ConfigurationSection config, File folder) throws Exception {
    String kind = config.getString("type", "SQLITE").toUpperCase(Locale.ROOT);
    if (kind.equals("SQLITE")) {
      Class.forName("org.sqlite.JDBC");
      connection =
          DriverManager.getConnection(
              "jdbc:sqlite:" + new File(folder, "cities.db").getAbsolutePath());
      try (Statement s = connection.createStatement()) {
        s.execute("PRAGMA journal_mode=WAL");
        s.execute("PRAGMA synchronous=FULL");
        s.execute("PRAGMA busy_timeout=5000");
      }
    } else if (kind.equals("MYSQL") || kind.equals("MARIADB")) {
      Class.forName("com.mysql.cj.jdbc.Driver");
      Properties p = new Properties();
      p.setProperty("user", config.getString("mysql.username", "root"));
      p.setProperty("password", config.getString("mysql.password", ""));
      p.setProperty("useSSL", String.valueOf(config.getBoolean("mysql.use-ssl", true)));
      p.setProperty("connectTimeout", "10000");
      p.setProperty("socketTimeout", "15000");
      connection =
          DriverManager.getConnection(
              "jdbc:mysql://"
                  + config.getString("mysql.host", "localhost")
                  + ":"
                  + config.getInt("mysql.port", 3306)
                  + "/"
                  + config.getString("mysql.database", "terranova"),
              p);
    } else throw new IllegalArgumentException("Database type inválido");
    SchemaMigrator.migrate(connection, kind.equals("SQLITE"));
    try (Statement s = connection.createStatement()) {
      s.execute("UPDATE cp_money_delivery SET status='REVIEW' WHERE status='PROCESSING'");
    }
  }

  private <T> CompletableFuture<T> work(Callable<T> c) {
    if (closed)
      return CompletableFuture.failedFuture(new IllegalStateException("Repository closed"));
    return CompletableFuture.supplyAsync(
        () -> {
          try {
            return c.call();
          } catch (Exception e) {
            throw new CompletionException(e);
          }
        },
        worker);
  }

  public CompletableFuture<List<Stored>> load() {
    return work(
        () -> {
          List<Stored> v = new ArrayList<>();
          try (PreparedStatement s =
                  connection.prepareStatement("SELECT id,revision,payload FROM cp_cities");
              ResultSet r = s.executeQuery()) {
            while (r.next())
              v.add(new Stored(UUID.fromString(r.getString(1)), r.getLong(2), r.getString(3)));
          }
          return v;
        });
  }

  public CompletableFuture<Void> mint(UUID token, String definition) {
    return work(
        () -> {
          try (PreparedStatement p =
              connection.prepareStatement(
                  "INSERT INTO cp_tokens(id,definition,location) VALUES(?,?,?)")) {
            p.setString(1, token.toString());
            p.setString(2, definition);
            p.setString(3, "ITEM");
            p.executeUpdate();
          }
          return null;
        });
  }

  public record TokenMint(UUID token, String definition) {}

  /** Mint identities and their deliveries together; a partial grant cannot survive a rollback. */
  public CompletableFuture<Void> mintDeliveries(List<TokenMint> tokens, List<Delivery> deliveries) {
    return work(
        () -> {
          connection.setAutoCommit(false);
          try {
            for (TokenMint token : tokens) {
              try (PreparedStatement q =
                  connection.prepareStatement(
                      "INSERT INTO cp_tokens(id,definition,location) VALUES(?,?,?)")) {
                q.setString(1, token.token().toString());
                q.setString(2, token.definition());
                q.setString(3, "ITEM");
                q.executeUpdate();
              }
            }
            for (Delivery delivery : deliveries) insertDelivery(delivery);
            connection.commit();
            return null;
          } catch (Exception error) {
            connection.rollback();
            throw error;
          } finally {
            connection.setAutoCommit(true);
          }
        });
  }

  public CompletableFuture<Void> commit(
      UUID id, long expected, String payload, List<TokenMove> moves, List<Delivery> deliveries) {
    return commit(id, expected, payload, moves, deliveries, List.of());
  }

  public CompletableFuture<Void> commit(
      UUID id,
      long expected,
      String payload,
      List<TokenMove> moves,
      List<Delivery> deliveries,
      List<MoneyDelivery> money) {
    return work(
        () -> {
          connection.setAutoCommit(false);
          try {
            if (id != null) {
              if (expected < 0) {
                try (PreparedStatement p =
                    connection.prepareStatement(
                        "INSERT INTO cp_cities(id,revision,payload) VALUES(?,0,?)")) {
                  p.setString(1, id.toString());
                  p.setString(2, payload);
                  p.executeUpdate();
                }
              } else if (payload == null) {
                try (PreparedStatement p =
                    connection.prepareStatement(
                        "DELETE FROM cp_cities WHERE id=? AND revision=?")) {
                  p.setString(1, id.toString());
                  p.setLong(2, expected);
                  if (p.executeUpdate() != 1) throw new SQLException("City revision conflict");
                }
              } else {
                try (PreparedStatement p =
                    connection.prepareStatement(
                        "UPDATE cp_cities SET revision=?,payload=? WHERE id=? AND revision=?")) {
                  p.setLong(1, expected + 1);
                  p.setString(2, payload);
                  p.setString(3, id.toString());
                  p.setLong(4, expected);
                  if (p.executeUpdate() != 1) throw new SQLException("City revision conflict");
                }
              }
            }
            for (TokenMove m : moves)
              try (PreparedStatement p =
                  connection.prepareStatement(
                      "UPDATE cp_tokens SET location=? WHERE id=? AND location=?")) {
                p.setString(1, m.to);
                p.setString(2, m.token.toString());
                p.setString(3, m.from);
                if (p.executeUpdate() != 1)
                  throw new SQLException("Token consumed, copied or invalid");
              }
            for (Delivery d : deliveries) insertDelivery(d);
            for (MoneyDelivery m : money) insertMoney(m);
            connection.commit();
            return null;
          } catch (Exception e) {
            connection.rollback();
            throw e;
          } finally {
            connection.setAutoCommit(true);
          }
        });
  }

  private void insertDelivery(Delivery d) throws SQLException {
    try (PreparedStatement p =
        connection.prepareStatement("INSERT INTO cp_delivery(id,player,payload) VALUES(?,?,?)")) {
      p.setString(1, d.id.toString());
      p.setString(2, d.player.toString());
      p.setString(3, d.payload);
      p.executeUpdate();
    }
  }

  public CompletableFuture<Void> deliver(Delivery d) {
    return work(
        () -> {
          insertDelivery(d);
          return null;
        });
  }

  private void insertMoney(MoneyDelivery m) throws SQLException {
    try (PreparedStatement q =
        connection.prepareStatement(
            "INSERT INTO cp_money_delivery(id,player,currency,amount,status) VALUES(?,?,?,?,?)")) {
      q.setString(1, m.id.toString());
      q.setString(2, m.player.toString());
      q.setString(3, m.currency);
      q.setDouble(4, m.amount);
      q.setString(5, m.status);
      q.executeUpdate();
    }
  }

  public CompletableFuture<List<MoneyDelivery>> money() {
    return work(
        () -> {
          List<MoneyDelivery> out = new ArrayList<>();
          try (PreparedStatement q =
                  connection.prepareStatement(
                      "SELECT id,player,currency,amount,status FROM cp_money_delivery");
              ResultSet r = q.executeQuery()) {
            while (r.next())
              out.add(
                  new MoneyDelivery(
                      UUID.fromString(r.getString(1)),
                      UUID.fromString(r.getString(2)),
                      r.getString(3),
                      r.getDouble(4),
                      r.getString(5)));
          }
          return out;
        });
  }

  public CompletableFuture<Boolean> moneyStatus(UUID id, String from, String to) {
    return work(
        () -> {
          try (PreparedStatement q =
              connection.prepareStatement(
                  to == null
                      ? "DELETE FROM cp_money_delivery WHERE id=? AND status=?"
                      : "UPDATE cp_money_delivery SET status=? WHERE id=? AND status=?")) {
            int n = 1;
            if (to != null) q.setString(n++, to);
            q.setString(n++, id.toString());
            q.setString(n, from);
            return q.executeUpdate() == 1;
          }
        });
  }

  public CompletableFuture<Void> barrier() {
    return work(() -> null);
  }

  public CompletableFuture<List<Delivery>> deliveries(UUID player) {
    return work(
        () -> {
          List<Delivery> a = new ArrayList<>();
          try (PreparedStatement p =
              connection.prepareStatement("SELECT id,payload FROM cp_delivery WHERE player=?")) {
            p.setString(1, player.toString());
            try (ResultSet r = p.executeQuery()) {
              while (r.next())
                a.add(new Delivery(UUID.fromString(r.getString(1)), player, r.getString(2)));
            }
          }
          return a;
        });
  }

  public CompletableFuture<Void> acknowledge(UUID id) {
    return work(
        () -> {
          try (PreparedStatement p =
              connection.prepareStatement("DELETE FROM cp_delivery WHERE id=?")) {
            p.setString(1, id.toString());
            p.executeUpdate();
          }
          return null;
        });
  }

  public CompletableFuture<Map<String, long[]>> events() {
    return work(
        () -> {
          Map<String, long[]> out = new LinkedHashMap<>();
          try (PreparedStatement p =
                  connection.prepareStatement("SELECT id,started,expires FROM cp_events");
              ResultSet r = p.executeQuery()) {
            while (r.next()) out.put(r.getString(1), new long[] {r.getLong(2), r.getLong(3)});
          }
          return out;
        });
  }

  public CompletableFuture<Void> event(String id, long started, long expires) {
    return work(
        () -> {
          connection.setAutoCommit(false);
          try {
            try (PreparedStatement p =
                connection.prepareStatement("DELETE FROM cp_events WHERE id=?")) {
              p.setString(1, id);
              p.executeUpdate();
            }
            if (expires > 0)
              try (PreparedStatement p =
                  connection.prepareStatement(
                      "INSERT INTO cp_events(id,started,expires) VALUES(?,?,?)")) {
                p.setString(1, id);
                p.setLong(2, started);
                p.setLong(3, expires);
                p.executeUpdate();
              }
            connection.commit();
            return null;
          } catch (Exception e) {
            connection.rollback();
            throw e;
          } finally {
            connection.setAutoCommit(true);
          }
        });
  }

  public CompletableFuture<Void> preference(UUID player, String raw) {
    return work(
        () -> {
          connection.setAutoCommit(false);
          try {
            try (PreparedStatement p =
                connection.prepareStatement("DELETE FROM cp_preferences WHERE player=?")) {
              p.setString(1, player.toString());
              p.executeUpdate();
            }
            try (PreparedStatement p =
                connection.prepareStatement(
                    "INSERT INTO cp_preferences(player,payload) VALUES(?,?)")) {
              p.setString(1, player.toString());
              p.setString(2, raw);
              p.executeUpdate();
            }
            connection.commit();
            return null;
          } catch (Exception e) {
            connection.rollback();
            throw e;
          } finally {
            connection.setAutoCommit(true);
          }
        });
  }

  @Override
  public void close() throws Exception {
    closed = true;
    worker.shutdown();
    if (!worker.awaitTermination(30, TimeUnit.SECONDS)) {
      worker.shutdownNow();
      throw new IllegalStateException("JDBC worker did not drain");
    }
    connection.close();
  }
}
