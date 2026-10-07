package dev.marco035xd.terranova.storage;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;

public final class SchemaMigrator {
  private SchemaMigrator() {}

  public static void migrate(Connection connection, boolean sqlite) throws Exception {
    try (Statement s = connection.createStatement()) {
      s.execute("CREATE TABLE IF NOT EXISTS cp_schema(version INTEGER PRIMARY KEY)");
    }
    int current;
    try (Statement s = connection.createStatement();
        ResultSet r = s.executeQuery("SELECT COALESCE(MAX(version),0) FROM cp_schema")) {
      r.next();
      current = r.getInt(1);
    }
    if (current > 2) throw new IllegalStateException("Database schema is newer than this plugin");
    String[] files = {"V1__city_state.sql", "V2__money_outbox.sql"};
    for (int v = current + 1; v <= 2; v++) {
      String path = "db/migrations/" + files[v - 1];
      try (InputStream stream = SchemaMigrator.class.getClassLoader().getResourceAsStream(path)) {
        if (stream == null) throw new FileNotFoundException(path);
        String sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        try (Statement s = connection.createStatement()) {
          for (String part : sql.split(";")) if (!part.isBlank()) s.execute(part.trim());
          if (v == 2 && !sqlite) {
            s.execute("ALTER TABLE cp_cities MODIFY payload LONGTEXT NOT NULL");
            s.execute("ALTER TABLE cp_delivery MODIFY payload LONGTEXT NOT NULL");
          }
        }
      }
      try (PreparedStatement p =
          connection.prepareStatement("INSERT INTO cp_schema(version) VALUES(?)")) {
        p.setInt(1, v);
        p.executeUpdate();
      }
    }
  }
}
