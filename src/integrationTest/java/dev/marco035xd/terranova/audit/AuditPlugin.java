package dev.marco035xd.terranova.audit;

import dev.marco035xd.terranova.TerranovaPlugin;
import dev.marco035xd.terranova.gui.GuiService.Screen;
import dev.marco035xd.terranova.model.*;
import dev.marco035xd.terranova.storage.*;
import dev.marco035xd.terranova.util.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.*;
import org.bukkit.*;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffectType;

/** Test-only companion, intentionally excluded from the production JAR. */
public final class AuditPlugin extends JavaPlugin {
  private static final UUID CORE = UUID.nameUUIDFromBytes("terranova-audit-core".getBytes()),
      OWNER = UUID.nameUUIDFromBytes("terranova-audit-owner".getBytes()),
      VISITOR = UUID.nameUUIDFromBytes("terranova-audit-visitor".getBytes()),
      GUARDIAN = UUID.nameUUIDFromBytes("terranova-audit-guardian".getBytes()),
      LOCK = UUID.nameUUIDFromBytes("terranova-audit-lock".getBytes());
  private static final UUID QUEUE_PLAYER =
      UUID.nameUUIDFromBytes("terranova-audit-queue".getBytes());

  private record Step(String name, Runnable start, BooleanSupplier ready, Runnable assertResult) {}

  private final List<Step> steps = new ArrayList<>();
  private final List<String> passed = new ArrayList<>();
  private TerranovaPlugin p;
  private FakePlayer owner, visitor;
  private World world;
  private Map<String, FlagRule> original;
  private boolean restart;
  private FakePlayer grantPlayer, queuePlayer;
  private UUID grantToken, queueToken;
  private ItemStack grantedCore;

  @Override
  public void onEnable() {
    getServer().getScheduler().runTaskLater(this, this::begin, 20);
  }

  private City city() {
    return p.cities().get(CORE);
  }

  private void check(boolean test, String message) {
    if (!test) throw new AssertionError(message);
  }

  private void step(String name, Runnable task, BooleanSupplier ready, Runnable assertions) {
    steps.add(new Step(name, task, ready, assertions));
  }

  private void checkStep(String name, Runnable assertions) {
    step(name, () -> {}, () -> true, assertions);
  }

  private boolean saved() {
    return city() != null && !p.cities().busy(CORE);
  }

  private void begin() {
    try {
      p = (TerranovaPlugin) getServer().getPluginManager().getPlugin("Terranova");
      check(p != null && p.isEnabled(), "plugin enabled");
      world = Bukkit.getWorlds().getFirst();
      world.getChunkAt(6, 6).load();
      owner = new FakePlayer(OWNER, "AuditOwner", new Location(world, 100, 70, 100));
      visitor = new FakePlayer(VISITOR, "AuditVisitor", new Location(world, 101, 70, 100));
      if (city() != null) {
        restart = true;
        restartChecks();
        run(0);
        return;
      }
      p.repository().mint(CORE, "core:classic").join();
      p.repository().mint(GUARDIAN, "crystal:guardian").join();
      p.repository().mint(LOCK, "crystal:raid-lockdown").join();
      step(
          "physical core creates protected city",
          () -> {
            world.getBlockAt(100, 70, 100).setType(Material.BEACON);
            p.cities()
                .create(
                    owner.player,
                    world.getBlockAt(100, 70, 100),
                    new Items.Identity("core", "classic", CORE));
          },
          this::saved,
          () -> {
            check(city().bounds.contains(world.getUID(), 85, -64, 85), "bounds include min corner");
            check(
                !p.decisions()
                    .canBreak(visitor.player, visitor.location, world.getBlockAt(101, 70, 100)),
                "visitor denied");
            check(
                p.decisions()
                    .canBreak(owner.player, owner.location, world.getBlockAt(101, 70, 100)),
                "owner allowed");
          });
      checkStep(
          "core hologram uses real text display entities",
          () ->
              check(
                  Arrays.stream(world.getChunkAt(6, 6).getEntities())
                      .anyMatch(e -> e instanceof TextDisplay),
                  "hologram exists"));
      checkStep(
          "main GUI and locked crystal states",
          () -> {
            p.gui().open(owner.player, city(), Screen.MAIN, 0);
            check(owner.opened.getItem(4).getType() == Material.BEACON, "main info");
            p.gui().open(owner.player, city(), Screen.CRYSTALS, 0);
            check(
                owner.opened.getItem(10).getType() == Material.PINK_STAINED_GLASS_PANE,
                "locked at level one");
          });
      step(
          "upgrade refreshes radius slots storage and hologram",
          () -> p.cities().upgrade(owner.player, city(), true),
          this::saved,
          () -> {
            check(city().level == 2, "level 2");
            check(p.cities().level(city()).radius() == 20, "radius");
            check(p.cities().level(city()).chestRows() == 2, "storage rows");
            p.gui().open(owner.player, city(), Screen.CRYSTALS, 0);
            check(
                owner.opened.getItem(10).getType() == Material.LIME_STAINED_GLASS_PANE,
                "available");
            check(
                owner.opened.getItem(12).getType() == Material.PINK_STAINED_GLASS_PANE,
                "remaining locked");
          });
      step(
          "guardian installs atomically and gives two effects",
          () -> {
            var d = p.catalog().crystals.get("guardian");
            owner.cursor = p.cities().crystalItem(d, GUARDIAN);
            p.cities().install(owner.player, city(), 0, owner.cursor, i -> owner.cursor = i);
          },
          this::saved,
          () -> {
            check(owner.cursor == null, "cursor consumed");
            p.effects().refresh(owner.player);
            check(
                owner.potions.containsKey(PotionEffectType.RESISTANCE)
                    && owner.potions.containsKey(PotionEffectType.REGENERATION),
                "both effects");
            p.effects().refresh(visitor.player);
            check(visitor.potions.isEmpty(), "only members");
          });
      checkStep(
          "leaving removes owned potion effects",
          () -> {
            owner.location.add(30, 0, 0);
            p.effects().refresh(owner.player);
            check(owner.potions.isEmpty(), "effects removed");
            owner.location.subtract(30, 0, 0);
          });
      checkStep(
          "crystal GUI double click shift and locked drag are cancelled",
          () -> {
            p.gui().open(owner.player, city(), Screen.CRYSTALS, 0);
            var view = owner.view();
            InventoryClickEvent doubleClick =
                new InventoryClickEvent(
                    view,
                    InventoryType.SlotType.CONTAINER,
                    10,
                    ClickType.DOUBLE_CLICK,
                    InventoryAction.COLLECT_TO_CURSOR);
            Bukkit.getPluginManager().callEvent(doubleClick);
            check(doubleClick.isCancelled(), "double click blocked");
            InventoryClickEvent shift =
                new InventoryClickEvent(
                    view,
                    InventoryType.SlotType.CONTAINER,
                    10,
                    ClickType.SHIFT_LEFT,
                    InventoryAction.MOVE_TO_OTHER_INVENTORY);
            Bukkit.getPluginManager().callEvent(shift);
            check(shift.isCancelled(), "shift blocked");
            ItemStack ordinary = new ItemStack(Material.DIAMOND);
            owner.cursor = ordinary;
            InventoryDragEvent drag =
                new InventoryDragEvent(view, null, ordinary, false, Map.of(12, ordinary));
            Bukkit.getPluginManager().callEvent(drag);
            check(drag.isCancelled() && owner.cursor == ordinary, "locked drag no consume");
            owner.cursor = null;
            check(city().crystals.size() == 1, "no duplication");
          });
      step(
          "upgrade level three",
          () -> p.cities().upgrade(owner.player, city(), true),
          this::saved,
          () -> check(city().level == 3, "level 3"));
      step(
          "raid-only modular lockdown installs inactive",
          () -> {
            owner.cursor = p.cities().crystalItem(p.catalog().crystals.get("raid-lockdown"), LOCK);
            p.cities().install(owner.player, city(), 1, owner.cursor, i -> owner.cursor = i);
          },
          this::saved,
          () ->
              check(
                  !p.effects().blocked(visitor.player, city(), Material.ENDER_PEARL),
                  "inactive outside raid"));
      step(
          "raid activates bossbar overrides and lockdown",
          () -> {
            original = Map.copyOf(city().flags);
            p.events().start("raid", 600000);
          },
          () -> p.events().get("raid") != null,
          () -> {
            check(p.events().get("raid").bar != null, "bossbar");
            check(
                p.decisions()
                    .canBreak(visitor.player, visitor.location, world.getBlockAt(101, 70, 100)),
                "raid break allowed");
            check(
                p.effects().blocked(visitor.player, city(), Material.ENDER_PEARL), "pearl blocked");
            check(
                !p.effects().blocked(owner.player, city(), Material.ENDER_PEARL), "member allowed");
            check(city().flags.equals(original), "base flags unchanged");
          });
      checkStep(
          "rapid raid hit does not increment twice",
          () -> {
            world.getBlockAt(102, 70, 100).setType(Material.STONE);
            p.hits().hit(visitor.player, city(), world.getBlockAt(102, 70, 100));
            String first = visitor.actionbar;
            p.hits().hit(visitor.player, city(), world.getBlockAt(102, 70, 100));
            check(visitor.actionbar.equals(first), "cooldown prevents second hit");
          });
      checkStep(
          "raid blacklist excludes bedrock",
          () -> {
            world.getBlockAt(103, 70, 100).setType(Material.BEDROCK);
            check(
                !p.decisions()
                    .canBreak(
                        visitor.player,
                        new Location(world, 103, 70, 100),
                        world.getBlockAt(103, 70, 100)),
                "bedrock denied");
          });
      step(
          "shield ignores raid overrides and prevents theft",
          () ->
              p.cities()
                  .update(
                      owner.player,
                      city(),
                      c -> {
                        c.shield = "guardian-30m";
                        c.shieldUntil = System.currentTimeMillis() + 1800000;
                      },
                      null),
          this::saved,
          () -> {
            check(
                !p.decisions()
                    .canBreak(visitor.player, visitor.location, world.getBlockAt(101, 70, 100)),
                "base break restored");
            check(!p.events().canSteal(visitor.player, city()), "theft blocked");
            check(
                !p.effects().blocked(visitor.player, city(), Material.ENDER_PEARL),
                "raid-only inactive under shield");
          });
      step(
          "removing shield returns temporary raid behavior",
          () ->
              p.cities()
                  .update(
                      owner.player,
                      city(),
                      c -> {
                        c.shield = "";
                        c.shieldUntil = 0;
                      },
                      null),
          this::saved,
          () -> check(p.events().canSteal(visitor.player, city()), "theft permitted"));
      step(
          "controlled theft removes crystal and queues item durably",
          () -> p.cities().removeCrystal(visitor.player, city(), 0, true),
          this::saved,
          () -> {
            check(!city().crystals.containsKey(0), "slot removed");
            check(!p.repository().deliveries(VISITOR).join().isEmpty(), "return outbox");
          });
      step(
          "raid termination restores exact original flags",
          () -> p.events().stop("raid"),
          () -> p.events().get("raid") == null,
          () -> {
            check(city().flags.equals(original), "exact flags preserved");
            check(
                !p.decisions().decide(visitor.player, visitor.location, "pvp").allowed(),
                "pvp deny restored");
            check(
                !p.effects().blocked(visitor.player, city(), Material.ENDER_PEARL),
                "lockdown deactivated");
          });
      step(
          "subzone volume and selection create inside city",
          () -> {
            p.catalog().settings.set("subzones.price-per-block", 0);
            p.subzones().select(owner.player, new Location(world, 105, 65, 105), true);
            p.subzones().select(owner.player, new Location(world, 107, 67, 107), false);
            check(p.subzones().selection(OWNER).box().volume() == 27, "inclusive volume");
            p.subzones().create(owner.player, city(), "market");
          },
          this::saved,
          () ->
              check(
                  city().subzones.size() == 1 && city().subzones.getFirst().bounds.volume() == 27,
                  "subzone saved"));
      checkStep(
          "escaping city and overlapping subzone selections rejected",
          () -> {
            check(
                p.subzones()
                    .validate(
                        city(),
                        new Cuboid(
                            world.getUID(),
                            city().bounds.maxX(),
                            65,
                            0,
                            city().bounds.maxX() + 1,
                            67,
                            1))
                    .name()
                    .equals("OUTSIDE_CITY"),
                "outside rejected");
            check(
                p.subzones()
                    .validate(city(), city().subzones.getFirst().bounds)
                    .name()
                    .equals("OVERLAPPING_SUBZONE"),
                "overlap rejected");
          });
      step(
          "members individual flags storage shield survive snapshot",
          () ->
              p.cities()
                  .update(
                      owner.player,
                      city(),
                      c -> {
                        c.members.add(UUID.nameUUIDFromBytes("audit-member".getBytes()));
                        c.individual.put(
                            UUID.nameUUIDFromBytes("audit-user".getBytes()),
                            new LinkedHashMap<>(Map.of("block-place", true)));
                        c.storage.put(0, new ItemStack(Material.DIAMOND, 5));
                        c.hologram = false;
                        c.shield = "guardian-2h";
                        c.shieldUntil = System.currentTimeMillis() + 7200000;
                      },
                      null),
          this::saved,
          () -> {
            City decoded =
                CityCodec.decode(
                    p.repository().load().join().stream()
                        .filter(s -> s.id().equals(CORE))
                        .findFirst()
                        .orElseThrow()
                        .payload());
            check(
                decoded.members.equals(city().members)
                    && decoded.individual.equals(city().individual),
                "membership persisted");
            check(
                decoded.storage.get(0).getAmount() == 5
                    && decoded.crystals.size() == 1
                    && decoded.shieldActive(System.currentTimeMillis()),
                "inventories crystals shield persisted");
          });
      checkStep(
          "public API exposes immutable city data",
          () -> {
            var provider =
                Bukkit.getServicesManager()
                    .getRegistration(dev.marco035xd.terranova.api.TerranovaApi.class);
            check(provider != null, "api service");
            var view = provider.getProvider().cityAt(owner.location).orElseThrow();
            try {
              view.members().clear();
              throw new AssertionError("mutable api");
            } catch (UnsupportedOperationException expected) {
            }
          });
      deliveryChecks();
      run(0);
    } catch (Throwable error) {
      fail(error);
    }
  }

  private ItemStack findCore(FakePlayer actor, String type) {
    return Arrays.stream(actor.contents)
        .filter(Objects::nonNull)
        .filter(
            item -> {
              var identity = p.items().identity(item);
              return identity != null
                  && identity.kind().equals("core")
                  && identity.id().equals(type);
            })
        .findFirst()
        .orElse(null);
  }

  private boolean delivered(FakePlayer actor) {
    return p.repository().deliveries(actor.id).join().isEmpty();
  }

  private void deliveryChecks() {
    int[] position = {300};
    for (String type : List.of("classic", "fortress")) {
      step(
          "give " + type + " delivers a signed core to inventory",
          () -> {
            int x = position[0];
            position[0] += 100;
            grantPlayer =
                new FakePlayer(
                    UUID.nameUUIDFromBytes(("grant-" + type).getBytes()),
                    "Marco035xd",
                    new Location(world, x, 70, x));
            p.cities().mint(grantPlayer.player, "core", type, 1);
          },
          () -> findCore(grantPlayer, type) != null && delivered(grantPlayer),
          () -> {
            grantedCore = findCore(grantPlayer, type).clone();
            grantToken = p.items().identity(grantedCore).token();
            check(
                grantedCore.getType() == p.catalog().types.get(type).material(),
                "correct physical type");
          });
      step(
          "create " + type + " from granted core",
          () -> {
            Arrays.fill(grantPlayer.contents, null);
            var block = world.getBlockAt(grantPlayer.location);
            block.setType(grantedCore.getType());
            p.cities().create(grantPlayer.player, block, p.items().identity(grantedCore));
          },
          () -> p.cities().get(grantToken) != null && !p.cities().busy(grantToken),
          () -> {});
      UUID guardian = UUID.nameUUIDFromBytes(("grant-guardian-" + type).getBytes());
      step(
          "prepare " + type + " stored items and crystal",
          () -> {
            p.repository().mint(guardian, "crystal:guardian").join();
            var city = p.cities().get(grantToken);
            p.cities()
                .transact(
                    grantPlayer.player,
                    city,
                    next -> {
                      next.level = 2;
                      var level = p.catalog().types.get(next.type).level(2);
                      next.bounds = p.cities().bounds(world, next.x, next.z, level.radius());
                      next.storage.put(0, new ItemStack(Material.DIAMOND, 5));
                      next.crystals.put(0, new City.InstalledCrystal("guardian", guardian));
                    },
                    List.of(
                        new JdbcRepository.TokenMove(
                            guardian, "ITEM", "CITY:" + grantToken + ":0")),
                    List.of(),
                    null,
                    null);
          },
          () -> !p.cities().busy(grantToken),
          () -> {});
      step(
          "delete " + type + " returns core crystal and stored items once",
          () -> p.cities().delete(grantPlayer.player, p.cities().get(grantToken)),
          () ->
              p.cities().get(grantToken) == null
                  && findCore(grantPlayer, type) != null
                  && delivered(grantPlayer),
          () -> {
            long cores =
                Arrays.stream(grantPlayer.contents)
                    .filter(Objects::nonNull)
                    .filter(
                        item -> {
                          var i = p.items().identity(item);
                          return i != null && i.kind().equals("core");
                        })
                    .count();
            check(cores == 1, "one returned core");
            check(
                p.items().identity(findCore(grantPlayer, type)).token().equals(grantToken),
                "reusable original identity");
            check(
                Arrays.stream(grantPlayer.contents)
                    .filter(Objects::nonNull)
                    .anyMatch(
                        item -> {
                          var i = p.items().identity(item);
                          return i != null && i.token().equals(guardian);
                        }),
                "installed crystal returned");
            check(
                Arrays.stream(grantPlayer.contents)
                    .filter(Objects::nonNull)
                    .anyMatch(item -> item.getType() == Material.DIAMOND && item.getAmount() == 5),
                "storage returned");
            check(
                world.getBlockAt(grantPlayer.location).getType() == Material.AIR,
                "physical core removed");
          });
    }
    step(
        "create full inventory return probe",
        () -> {
          ItemStack core = findCore(grantPlayer, "fortress").clone();
          queueToken = p.items().identity(core).token();
          Arrays.fill(grantPlayer.contents, null);
          queuePlayer =
              new FakePlayer(QUEUE_PLAYER, "QueueOwner", new Location(world, 600, 70, 600));
          for (int i = 0; i < 36; i++) queuePlayer.contents[i] = new ItemStack(Material.STONE, 64);
          world.getBlockAt(queuePlayer.location).setType(core.getType());
          p.cities()
              .create(
                  queuePlayer.player,
                  world.getBlockAt(queuePlayer.location),
                  p.items().identity(core));
        },
        () -> p.cities().get(queueToken) != null && !p.cities().busy(queueToken),
        () -> {});
    step(
        "delete with full inventory retains a durable core return",
        () -> p.cities().delete(queuePlayer.player, p.cities().get(queueToken)),
        () ->
            p.cities().get(queueToken) == null
                && p.repository().deliveries(QUEUE_PLAYER).join().size() == 1,
        () -> check(findCore(queuePlayer, "fortress") == null, "full inventory not overwritten"));
    checkStep(
        "authenticated objects survive a namespace rename",
        () -> {
          ItemStack item = p.cities().coreItem(p.catalog().types.get("classic"), UUID.randomUUID());
          var identity = p.items().identity(item);
          var meta = item.getItemMeta();
          var data = meta.getPersistentDataContainer();
          for (NamespacedKey key : new HashSet<>(data.getKeys())) {
            if (key.getKey().equals("signature")) {
              byte[] value = data.get(key, org.bukkit.persistence.PersistentDataType.BYTE_ARRAY);
              data.set(
                  new NamespacedKey("legacyrealm", key.getKey()),
                  org.bukkit.persistence.PersistentDataType.BYTE_ARRAY,
                  value);
            } else {
              String value = data.get(key, org.bukkit.persistence.PersistentDataType.STRING);
              data.set(
                  new NamespacedKey("legacyrealm", key.getKey()),
                  org.bukkit.persistence.PersistentDataType.STRING,
                  value);
            }
            data.remove(key);
          }
          item.setItemMeta(meta);
          check(
              identity.equals(p.items().identity(item)),
              "same authenticated identity across namespace");
          meta = item.getItemMeta();
          data = meta.getPersistentDataContainer();
          data.set(
              new NamespacedKey("legacyrealm", "signature"),
              org.bukkit.persistence.PersistentDataType.BYTE_ARRAY,
              new byte[32]);
          item.setItemMeta(meta);
          check(p.items().identity(item) == null, "forged migration signature rejected");
        });
  }

  private void restartChecks() {
    checkStep(
        "restart restores city level and bounds",
        () ->
            check(
                city().level == 3 && p.cities().level(city()).radius() == 25, "level and radius"));
    checkStep(
        "restart restores crystals and stored items",
        () ->
            check(
                city().crystals.get(1).id().equals("raid-lockdown")
                    && city().storage.get(0).getAmount() == 5,
                "items"));
    checkStep(
        "restart restores members flags subzones shield preferences",
        () ->
            check(
                city().members.size() == 1
                    && !city().individual.isEmpty()
                    && city().subzones.size() == 1
                    && city().shieldActive(System.currentTimeMillis())
                    && !city().hologram,
                "complete aggregate"));
    checkStep(
        "restart has no permanent raid override",
        () ->
            check(
                p.events().get("raid") == null
                    && !p.decisions().decide(visitor.player, visitor.location, "pvp").allowed(),
                "normal flags"));
    checkStep(
        "restart preserves pending full inventory core",
        () -> {
          check(
              p.repository().deliveries(QUEUE_PLAYER).join().size() == 1,
              "core still queued after restart");
          queuePlayer =
              new FakePlayer(QUEUE_PLAYER, "QueueOwner", new Location(world, 600, 70, 600));
          check(p.cities().at(queuePlayer.location) == null, "deleted city stays deleted");
        });
    step(
        "claim delivers pending core once after restart",
        () -> p.delivery().claim(queuePlayer.player),
        () -> findCore(queuePlayer, "fortress") != null && delivered(queuePlayer),
        () -> {
          check(
              Arrays.stream(queuePlayer.contents).filter(Objects::nonNull).count() == 1,
              "one reclaimed core");
          p.delivery().claim(queuePlayer.player);
        });
  }

  private void run(int index) {
    if (index == steps.size()) {
      finish();
      return;
    }
    Step s = steps.get(index);
    try {
      s.start.run();
    } catch (Throwable error) {
      fail(error);
      return;
    }
    final int[] ticks = {0};
    new org.bukkit.scheduler.BukkitRunnable() {
      public void run() {
        try {
          if (++ticks[0] > 200) throw new AssertionError("Timeout: " + s.name);
          if (!s.ready.getAsBoolean()) return;
          s.assertResult.run();
          passed.add(s.name);
          getLogger().info("PASS " + s.name);
          cancel();
          getServer()
              .getScheduler()
              .runTaskLater(AuditPlugin.this, () -> AuditPlugin.this.run(index + 1), 1);
        } catch (Throwable error) {
          cancel();
          fail(error);
        }
      }
    }.runTaskTimer(this, 1, 1);
  }

  private void finish() {
    try {
      getDataFolder().mkdirs();
      String phase = restart ? "restart" : "initial";
      Files.writeString(
          new java.io.File(getDataFolder(), phase + "-results.txt").toPath(),
          "PASS " + passed.size() + "\n" + String.join("\n", passed) + "\n");
      getLogger().info("AUDIT COMPLETE " + passed.size() + " passed");
    } catch (Exception e) {
      fail(e);
    }
  }

  private void fail(Throwable error) {
    getLogger().log(java.util.logging.Level.SEVERE, "AUDIT FAILED", error);
    try {
      getDataFolder().mkdirs();
      Files.writeString(
          new java.io.File(getDataFolder(), "failure.txt").toPath(), error.toString());
    } catch (Exception ignored) {
    }
  }
}
