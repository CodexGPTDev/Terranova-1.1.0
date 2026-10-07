package dev.marco035xd.terranova.service;

import dev.marco035xd.terranova.TerranovaPlugin;
import dev.marco035xd.terranova.storage.*;
import java.util.*;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** Persistent outbox. Signed crystal identities additionally prevent duplicate installations. */
public final class DeliveryService {
  private final TerranovaPlugin p;
  private final Set<UUID> draining = new HashSet<>();
  private final Map<UUID, Set<UUID>> seen = new HashMap<>();
  private final Set<UUID> backlogNotice = new HashSet<>();

  public DeliveryService(TerranovaPlugin p) {
    this.p = p;
  }

  public JdbcRepository.Delivery delivery(UUID player, ItemStack item) {
    return new JdbcRepository.Delivery(UUID.randomUUID(), player, CityCodec.item(item));
  }

  public void queue(UUID player, ItemStack item) {
    if (item == null || item.getType().isAir()) return;
    p.repository()
        .deliver(delivery(player, item))
        .whenComplete(
            (v, e) ->
                p.main(
                    () -> {
                      if (e != null) p.failure(e);
                      else drain(Bukkit.getPlayer(player));
                    }));
  }

  public void drain(Player player) {
    drain(player, false);
  }

  public void claim(Player player) {
    drain(player, true);
  }

  private void drain(Player player, boolean manual) {
    if (player == null || !player.isOnline()) return;
    if (!draining.add(player.getUniqueId())) {
      if (manual) p.message(player, "delivery-processing");
      return;
    }
    UUID u = player.getUniqueId();
    p.repository()
        .deliveries(u)
        .whenComplete(
            (list, e) ->
                p.main(
                    () -> {
                      try {
                        if (e != null) {
                          p.failure(e);
                          if (manual) p.message(player, "operation-failed");
                          return;
                        }
                        // Keep the captured connection: an old session must never receive a
                        // reconnect's items.
                        Player current = player;
                        if (!current.isOnline()) return;
                        Set<UUID> session = seen.computeIfAbsent(u, k -> new HashSet<>());
                        int received = 0;
                        int pending = 0;
                        for (var d : list) {
                          boolean already = session.contains(d.id());
                          for (ItemStack i : current.getInventory().getContents())
                            already |= d.id().equals(p.items().receipt(i));
                          if (already) {
                            p.repository().acknowledge(d.id());
                            continue;
                          }
                          int slot = current.getInventory().firstEmpty();
                          if (slot < 0) {
                            pending++;
                            continue;
                          }
                          ItemStack i = CityCodec.item(d.payload());
                          p.items().receipt(i, d.id());
                          current.getInventory().setItem(slot, i);
                          session.add(d.id());
                          received++;
                          p.repository()
                              .acknowledge(d.id())
                              .exceptionally(
                                  x -> {
                                    p.failure(x);
                                    return null;
                                  });
                        }
                        if (received > 0)
                          p.message(current, "delivery-received", Map.of("count", received));
                        if (pending > 0) {
                          if (manual || backlogNotice.add(u))
                            p.message(current, "delivery-pending", Map.of("count", pending));
                        } else {
                          backlogNotice.remove(u);
                          if (manual && received == 0) p.message(current, "delivery-empty");
                        }
                      } finally {
                        draining.remove(u);
                      }
                    }));
  }

  public void forget(UUID u) {
    seen.remove(u);
    backlogNotice.remove(u);
  }
}
