package dev.marco035xd.terranova.hook;

import dev.marco035xd.terranova.TerranovaPlugin;
import dev.marco035xd.terranova.config.ConfigCatalog;
import dev.marco035xd.terranova.util.Text;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import org.bukkit.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** Soft APIs, result-checked withdrawals and compensating refunds for mixed costs. */
public final class EconomyService {
  public static final class Receipt {
    public final UUID player;
    public final Map<String, Double> money = new LinkedHashMap<>();
    public final List<ItemStack> items = new ArrayList<>();

    public Receipt(UUID u) {
      player = u;
    }
  }

  private final TerranovaPlugin p;
  private Object vault, excellent;

  public EconomyService(TerranovaPlugin p) {
    this.p = p;
    vault = service("net.milkbowl.vault.economy.Economy");
    excellent = service("su.nightexpress.excellenteconomy.api.ExcellentEconomyAPI");
    if (excellent == null)
      excellent = service("su.nightexpress.coinsengine.api.ExcellentEconomyAPI");
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private Object service(String name) {
    try {
      Class cls = Class.forName(name);
      var r = Bukkit.getServicesManager().getRegistration(cls);
      return r == null ? null : r.getProvider();
    } catch (ClassNotFoundException e) {
      return null;
    }
  }

  public boolean available(String currency) {
    return currency.equals("vault")
        ? vault != null
        : currency.startsWith("ee:") && excellent != null;
  }

  public List<String> currencies() {
    List<String> out = new ArrayList<>();
    if (vault != null) out.add("vault");
    if (excellent != null)
      try {
        Object result = excellent.getClass().getMethod("getCurrencies").invoke(excellent);
        if (result instanceof Collection<?> list)
          for (Object c : list) out.add("ee:" + c.getClass().getMethod("getId").invoke(c));
      } catch (ReflectiveOperationException e) {
        p.failure(e);
      }
    return out;
  }

  public CompletableFuture<Receipt> charge(Player player, ConfigurationSection cost) {
    Map<String, Double> money = new LinkedHashMap<>();
    List<ItemStack> items = new ArrayList<>();
    if (cost != null) {
      double n = Text.money(cost.getDouble("vault"));
      if (n > 0) money.put("vault", n);
      ConfigurationSection ee = cost.getConfigurationSection("excellent-economy");
      if (ee != null)
        for (String key : ee.getKeys(false)) {
          double v = Text.money(ee.getDouble(key));
          if (v > 0) money.put("ee:" + key, v);
        }
      for (Map<?, ?> m : cost.getMapList("items"))
        items.add(
            new ItemStack(
                ConfigCatalog.material(String.valueOf(m.get("material"))),
                ((Number) m.get("amount")).intValue()));
    }
    return charge(player, money, items);
  }

  public CompletableFuture<Receipt> charge(Player player, String currency, double price) {
    return charge(player, Map.of(currency, Text.money(price)), List.of());
  }

  private CompletableFuture<Receipt> charge(
      Player player, Map<String, Double> cost, List<ItemStack> required) {
    Map<Material, Integer> totals = new LinkedHashMap<>();
    for (ItemStack i : required) totals.merge(i.getType(), i.getAmount(), Math::addExact);
    required =
        totals.entrySet().stream().map(e -> new ItemStack(e.getKey(), e.getValue())).toList();
    Receipt receipt = new Receipt(player.getUniqueId());
    for (ItemStack i : required)
      if (count(player, i.getType()) < i.getAmount())
        return CompletableFuture.failedFuture(
            new IllegalArgumentException("Faltan objetos: " + i.getType()));
    CompletableFuture<Receipt> result = new CompletableFuture<>();
    Iterator<Map.Entry<String, Double>> iterator = cost.entrySet().iterator();
    chargeNext(player, iterator, required, receipt, result);
    return result;
  }

  private void chargeNext(
      Player player,
      Iterator<Map.Entry<String, Double>> it,
      List<ItemStack> required,
      Receipt receipt,
      CompletableFuture<Receipt> result) {
    if (!player.isOnline()) {
      refund(receipt);
      result.completeExceptionally(new IllegalStateException("Jugador desconectado"));
      return;
    }
    if (it.hasNext()) {
      var e = it.next();
      withdraw(player.getUniqueId(), e.getKey(), e.getValue())
          .whenComplete(
              (ok, error) ->
                  p.main(
                      () -> {
                        if (error != null || !Boolean.TRUE.equals(ok)) {
                          refund(receipt);
                          result.completeExceptionally(
                              new IllegalArgumentException(
                                  "Fondos insuficientes o economía no disponible: " + e.getKey()));
                        } else {
                          receipt.money.put(e.getKey(), e.getValue());
                          chargeNext(player, it, required, receipt, result);
                        }
                      }));
      return;
    }
    for (ItemStack i : required)
      if (count(player, i.getType()) < i.getAmount()) {
        refund(receipt);
        result.completeExceptionally(
            new IllegalArgumentException("Los objetos requeridos cambiaron"));
        return;
      }
    for (ItemStack i : required) {
      int remaining = i.getAmount();
      for (int s = 0; s < player.getInventory().getStorageContents().length && remaining > 0; s++) {
        ItemStack stack = player.getInventory().getItem(s);
        if (stack != null && stack.getType() == i.getType() && p.items().identity(stack) == null) {
          int take = Math.min(remaining, stack.getAmount());
          ItemStack part = stack.clone();
          part.setAmount(take);
          receipt.items.add(part);
          remaining -= take;
          stack.setAmount(stack.getAmount() - take);
          player.getInventory().setItem(s, stack.getAmount() == 0 ? null : stack);
        }
      }
    }
    result.complete(receipt);
  }

  private int count(Player player, Material type) {
    int total = 0;
    for (ItemStack i : player.getInventory().getStorageContents())
      if (i != null && i.getType() == type && p.items().identity(i) == null) total += i.getAmount();
    return total;
  }

  public CompletableFuture<Boolean> withdraw(UUID u, String currency, double amount) {
    return operation(u, currency, amount, false);
  }

  public CompletableFuture<Boolean> deposit(UUID u, String currency, double amount) {
    return operation(u, currency, amount, true);
  }

  private CompletableFuture<Boolean> operation(UUID u, String c, double n, boolean deposit) {
    try {
      if (n == 0) return CompletableFuture.completedFuture(true);
      if (!available(c)) throw new IllegalStateException("Economía no disponible " + c);
      if (c.equals("vault")) {
        Object r =
            vault
                .getClass()
                .getMethod(
                    deposit ? "depositPlayer" : "withdrawPlayer", OfflinePlayer.class, double.class)
                .invoke(vault, Bukkit.getOfflinePlayer(u), n);
        boolean ok = (boolean) r.getClass().getMethod("transactionSuccess").invoke(r);
        return CompletableFuture.completedFuture(ok);
      }
      Object future =
          excellent
              .getClass()
              .getMethod(
                  deposit ? "depositAsync" : "withdrawAsync",
                  UUID.class,
                  String.class,
                  double.class)
              .invoke(excellent, u, c.substring(3), n);
      return ((CompletableFuture<?>) future)
          .thenApply(
              r -> {
                if (r instanceof Boolean b) return b;
                try {
                  return (boolean) r.getClass().getMethod("success").invoke(r);
                } catch (ReflectiveOperationException a) {
                  throw new CompletionException(a);
                }
              });
    } catch (Exception e) {
      return CompletableFuture.failedFuture(e);
    }
  }

  public void refund(Receipt r) {
    for (ItemStack i : r.items) p.delivery().queue(r.player, i);
    r.items.clear();
    for (var e : r.money.entrySet()) p.moneyRecovery().queue(r.player, e.getKey(), e.getValue());
    r.money.clear();
  }
}
