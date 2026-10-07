package dev.marco035xd.terranova;

import static org.junit.jupiter.api.Assertions.*;

import dev.marco035xd.terranova.storage.JdbcRepository;
import java.nio.file.Path;
import java.util.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RepositoryTest {
  @TempDir Path dir;

  private JdbcRepository open() throws Exception {
    YamlConfiguration y = new YamlConfiguration();
    y.set("type", "SQLITE");
    return new JdbcRepository(y, dir.toFile());
  }

  @Test
  void duplicateInGrantBatchRollsBackIdentitiesAndDeliveries() throws Exception {
    try (var r = open()) {
      UUID token = UUID.randomUUID(), player = UUID.randomUUID();
      assertThrows(
          RuntimeException.class,
          () ->
              r.mintDeliveries(
                      List.of(
                          new JdbcRepository.TokenMint(token, "core:classic"),
                          new JdbcRepository.TokenMint(token, "core:fortress")),
                      List.of(new JdbcRepository.Delivery(UUID.randomUUID(), player, "core")))
                  .join());
      assertTrue(r.deliveries(player).join().isEmpty());
      // If the first insert was rolled back, the identity can be minted normally.
      r.mintDeliveries(
              List.of(new JdbcRepository.TokenMint(token, "core:classic")),
              List.of(new JdbcRepository.Delivery(UUID.randomUUID(), player, "core")))
          .join();
      assertEquals(1, r.deliveries(player).join().size());
    }
  }

  @Test
  void deletedCityReturnsReusableCoreAndSurvivesRestart() throws Exception {
    UUID core = UUID.randomUUID(), player = UUID.randomUUID();
    try (var r = open()) {
      r.mint(core, "core:fortress").join();
      r.commit(
              core,
              -1,
              "city",
              List.of(new JdbcRepository.TokenMove(core, "ITEM", "CORE:" + core)),
              List.of())
          .join();
      r.commit(
              core,
              0,
              null,
              List.of(new JdbcRepository.TokenMove(core, "CORE:" + core, "ITEM")),
              List.of(new JdbcRepository.Delivery(UUID.randomUUID(), player, "returned-fortress")))
          .join();
      assertTrue(r.load().join().isEmpty());
    }
    try (var r = open()) {
      assertEquals("returned-fortress", r.deliveries(player).join().getFirst().payload());
      r.commit(
              core,
              -1,
              "recreated",
              List.of(new JdbcRepository.TokenMove(core, "ITEM", "CORE:" + core)),
              List.of())
          .join();
      assertEquals("recreated", r.load().join().getFirst().payload());
    }
  }

  @Test
  void failedDeletionKeepsCityAndDoesNotReturnAnotherCore() throws Exception {
    try (var r = open()) {
      UUID core = UUID.randomUUID(), player = UUID.randomUUID();
      r.mint(core, "core:classic").join();
      r.commit(
              core,
              -1,
              "protected",
              List.of(new JdbcRepository.TokenMove(core, "ITEM", "CORE:" + core)),
              List.of())
          .join();
      assertThrows(
          RuntimeException.class,
          () ->
              r.commit(
                      core,
                      0,
                      null,
                      List.of(new JdbcRepository.TokenMove(core, "WRONG", "ITEM")),
                      List.of(
                          new JdbcRepository.Delivery(
                              UUID.randomUUID(), player, "must-not-deliver")))
                  .join());
      assertEquals("protected", r.load().join().getFirst().payload());
      assertTrue(r.deliveries(player).join().isEmpty());
    }
  }

  @Test
  void stateAndGlobalEventSurviveRepositoryRestart() throws Exception {
    UUID id = UUID.randomUUID();
    try (var r = open()) {
      r.commit(id, -1, "original", List.of(), List.of()).join();
      r.commit(id, 0, "upgraded", List.of(), List.of()).join();
      r.event("raid", 100, 200).join();
    }
    try (var r = open()) {
      assertEquals("upgraded", r.load().join().getFirst().payload());
      assertEquals(1, r.load().join().getFirst().revision());
      assertArrayEquals(new long[] {100, 200}, r.events().join().get("raid"));
    }
  }

  @Test
  void staleRevisionCannotOverwriteAnotherCommittedOperation() throws Exception {
    try (var r = open()) {
      UUID id = UUID.randomUUID();
      r.commit(id, -1, "base", List.of(), List.of()).join();
      r.commit(id, 0, "first", List.of(), List.of()).join();
      assertThrows(
          RuntimeException.class, () -> r.commit(id, 0, "stale", List.of(), List.of()).join());
      assertEquals("first", r.load().join().getFirst().payload());
    }
  }

  @Test
  void duplicateCrystalTokenRollsBackCityMutationAndDelivery() throws Exception {
    try (var r = open()) {
      UUID city = UUID.randomUUID(), token = UUID.randomUUID(), player = UUID.randomUUID();
      r.mint(token, "crystal:guardian").join();
      r.commit(
              city,
              -1,
              "one",
              List.of(new JdbcRepository.TokenMove(token, "ITEM", "CITY:one:0")),
              List.of())
          .join();
      var delivery = new JdbcRepository.Delivery(UUID.randomUUID(), player, "item");
      assertThrows(
          RuntimeException.class,
          () ->
              r.commit(
                      city,
                      0,
                      "changed",
                      List.of(new JdbcRepository.TokenMove(token, "ITEM", "CITY:one:1")),
                      List.of(delivery))
                  .join());
      assertEquals("one", r.load().join().getFirst().payload());
      assertTrue(r.deliveries(player).join().isEmpty());
    }
  }

  @Test
  void removalAndReturnAreOneDurableTransaction() throws Exception {
    UUID city = UUID.randomUUID(),
        token = UUID.randomUUID(),
        player = UUID.randomUUID(),
        delivery = UUID.randomUUID();
    try (var r = open()) {
      r.mint(token, "crystal:guardian").join();
      r.commit(
              city,
              -1,
              "installed",
              List.of(new JdbcRepository.TokenMove(token, "ITEM", "CITY:one:0")),
              List.of())
          .join();
      r.commit(
              city,
              0,
              "removed",
              List.of(new JdbcRepository.TokenMove(token, "CITY:one:0", "ITEM")),
              List.of(new JdbcRepository.Delivery(delivery, player, "returned")))
          .join();
    }
    try (var r = open()) {
      assertEquals("removed", r.load().join().getFirst().payload());
      assertEquals("returned", r.deliveries(player).join().getFirst().payload());
      r.acknowledge(delivery).join();
      assertTrue(r.deliveries(player).join().isEmpty());
    }
  }

  @Test
  void salePaymentCommitsWithCityAndUncertainPaymentIsQuarantinedOnRestart() throws Exception {
    UUID city = UUID.randomUUID(), id = UUID.randomUUID();
    try (var r = open()) {
      r.commit(
              city,
              -1,
              "sold",
              List.of(),
              List.of(),
              List.of(
                  new JdbcRepository.MoneyDelivery(id, UUID.randomUUID(), "vault", 500, "READY")))
          .join();
      assertEquals(500, r.money().join().getFirst().amount());
      assertTrue(r.moneyStatus(id, "READY", "PROCESSING").join());
      assertFalse(r.moneyStatus(id, "READY", "PROCESSING").join());
    }
    try (var r = open()) {
      assertEquals("REVIEW", r.money().join().getFirst().status());
      assertTrue(r.moneyStatus(id, "REVIEW", null).join());
      assertTrue(r.money().join().isEmpty());
    }
  }
}
