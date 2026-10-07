package dev.marco035xd.terranova.service;

import dev.marco035xd.terranova.TerranovaPlugin;
import dev.marco035xd.terranova.storage.JdbcRepository;
import dev.marco035xd.terranova.util.Text;
import java.util.*;

/**
 * A persisted payment intent is committed with a sale; ambiguous external outcomes are quarantined.
 */
public final class MoneyRecoveryService {
  private final TerranovaPlugin p;
  private boolean polling;
  private final Set<UUID> pending = new HashSet<>();

  public MoneyRecoveryService(TerranovaPlugin p) {
    this.p = p;
  }

  public JdbcRepository.MoneyDelivery payment(UUID player, String currency, double amount) {
    return new JdbcRepository.MoneyDelivery(
        UUID.randomUUID(), player, currency, Text.money(amount), "READY");
  }

  public void queue(UUID player, String currency, double amount) {
    var row = payment(player, currency, amount);
    p.repository()
        .commit(null, 0, null, List.of(), List.of(), List.of(row))
        .whenComplete(
            (v, e) ->
                p.main(
                    () -> {
                      if (e != null) p.failure(e);
                      else drain();
                    }));
  }

  public void drain() {
    if (polling) return;
    polling = true;
    p.repository()
        .money()
        .whenComplete(
            (rows, error) ->
                p.main(
                    () -> {
                      polling = false;
                      if (error != null) {
                        p.failure(error);
                        return;
                      }
                      for (var row : rows) {
                        if (!row.status().equals("READY")
                            || !p.economy().available(row.currency())
                            || !pending.add(row.id())) continue;
                        p.repository()
                            .moneyStatus(row.id(), "READY", "PROCESSING")
                            .whenComplete(
                                (claimed, claimError) ->
                                    p.main(
                                        () -> {
                                          if (claimError != null || !Boolean.TRUE.equals(claimed)) {
                                            pending.remove(row.id());
                                            if (claimError != null) p.failure(claimError);
                                            return;
                                          }
                                          p.economy()
                                              .deposit(row.player(), row.currency(), row.amount())
                                              .whenComplete(
                                                  (ok, e) -> {
                                                    String next =
                                                        e == null
                                                            ? (Boolean.TRUE.equals(ok)
                                                                ? null
                                                                : "READY")
                                                            : "REVIEW";
                                                    p.repository()
                                                        .moneyStatus(row.id(), "PROCESSING", next)
                                                        .whenComplete(
                                                            (v, saveError) ->
                                                                p.main(
                                                                    () -> {
                                                                      pending.remove(row.id());
                                                                      if (saveError != null)
                                                                        p.failure(saveError);
                                                                      if (e != null)
                                                                        p.getLogger()
                                                                            .warning(
                                                                                "Pago en revisión "
                                                                                    + row.id()
                                                                                    + "; /city"
                                                                                    + " admin"
                                                                                    + " finance"
                                                                                    + " list");
                                                                    }));
                                                  });
                                        }));
                      }
                    }));
  }

  public void resolve(UUID id, boolean retry) {
    p.repository()
        .moneyStatus(id, "REVIEW", retry ? "READY" : null)
        .whenComplete(
            (v, e) ->
                p.main(
                    () -> {
                      if (e != null) p.failure(e);
                      else drain();
                    }));
  }
}
