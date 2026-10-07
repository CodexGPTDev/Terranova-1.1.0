package dev.marco035xd.terranova.service;

import dev.marco035xd.terranova.TerranovaPlugin;
import dev.marco035xd.terranova.config.ConfigCatalog;
import dev.marco035xd.terranova.hook.EconomyService;
import dev.marco035xd.terranova.model.*;
import dev.marco035xd.terranova.storage.*;
import dev.marco035xd.terranova.util.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

public final class CityService {
  private final TerranovaPlugin p;
  private final Map<UUID, City> cities = new java.util.concurrent.ConcurrentHashMap<>();
  private final Set<UUID> busy = new HashSet<>();
  private final SpatialIndex index;

  public CityService(TerranovaPlugin p) {
    this.p = p;
    index = new SpatialIndex(p.catalog().settings.getInt("performance.max-index-chunks", 4096));
  }

  public void load() {
    for (var s : p.repository().load().join()) {
      City c = CityCodec.decode(s.payload());
      c.revision = s.revision();
      p.catalog().types.get(c.type).level(c.level);
      put(c);
    }
  }

  private void put(City c) {
    cities.put(c.id, c);
    index.put(c.id, c.bounds);
  }

  public Collection<City> all() {
    return List.copyOf(cities.values());
  }

  public List<City> nearby(Location location, int distance) {
    return index
        .nearby(
            location.getWorld().getUID(),
            location.getBlockX(),
            location.getBlockZ(),
            Math.min(512, distance))
        .stream()
        .map(cities::get)
        .filter(Objects::nonNull)
        .toList();
  }

  public City get(UUID id) {
    return id == null ? null : cities.get(id);
  }

  public City at(Location l) {
    if (l == null || l.getWorld() == null) return null;
    return get(index.at(l.getWorld().getUID(), l.getBlockX(), l.getBlockY(), l.getBlockZ()));
  }

  public City primary(UUID u) {
    return cities.values().stream()
        .filter(c -> c.isOwner(u))
        .findFirst()
        .orElseGet(
            () -> cities.values().stream().filter(c -> c.isMember(u)).findFirst().orElse(null));
  }

  public boolean core(City c, Block b) {
    return c != null
        && c.world.equals(b.getWorld().getUID())
        && c.x == b.getX()
        && c.y == b.getY()
        && c.z == b.getZ();
  }

  public ConfigCatalog.Level level(City c) {
    return p.catalog().types.get(c.type).level(c.level);
  }

  public boolean busy(UUID id) {
    return busy.contains(id);
  }

  public boolean manager(Player player, City c) {
    return c != null
        && (c.isOwner(player.getUniqueId()) || player.hasPermission("terranova.admin"));
  }

  public int limit(Player player) {
    String prefix = "terranova.protections.limit.";
    if (player.hasPermission(prefix + "unlimited")) return Integer.MAX_VALUE;
    int max = p.catalog().settings.getInt("limits.per-player", 2);
    for (var a : player.getEffectivePermissions())
      if (a.getValue() && a.getPermission().startsWith(prefix))
        try {
          max = Math.max(max, Integer.parseInt(a.getPermission().substring(prefix.length())));
        } catch (NumberFormatException ignored) {
        }
    return max;
  }

  public Cuboid bounds(World w, int x, int z, int radius) {
    return new Cuboid(
        w.getUID(),
        Math.subtractExact(x, radius),
        w.getMinHeight(),
        Math.subtractExact(z, radius),
        Math.addExact(x, radius),
        w.getMaxHeight() - 1,
        Math.addExact(z, radius));
  }

  public void validateCreation(Player player, Block block, Items.Identity item) {
    draftCreation(player, block, item);
  }

  private City draftCreation(Player player, Block block, Items.Identity item) {
    if (cities.containsKey(item.token()) || busy.contains(item.token()))
      throw new IllegalArgumentException("Este núcleo ya está registrado");
    var type = p.catalog().types.get(item.id());
    if (type == null) throw new IllegalArgumentException("Tipo de núcleo no disponible");
    if (!p.catalog().worldEnabled(block.getWorld()))
      throw new IllegalArgumentException("Mundo deshabilitado");
    long owned = cities.values().stream().filter(c -> c.isOwner(player.getUniqueId())).count();
    if (owned >= limit(player)) throw new IllegalArgumentException("Límite de ciudades alcanzado");
    String worldPerm =
        "terranova.protections.limit.world."
            + block.getWorld().getName().toLowerCase(Locale.ROOT)
            + ".";
    int wlimit = p.catalog().settings.getInt("limits.per-world", 2);
    for (var a : player.getEffectivePermissions())
      if (a.getValue() && a.getPermission().startsWith(worldPerm))
        try {
          wlimit =
              Math.max(wlimit, Integer.parseInt(a.getPermission().substring(worldPerm.length())));
        } catch (NumberFormatException ignored) {
        }
    if (!player.hasPermission(worldPerm + "unlimited")
        && !player.hasPermission("terranova.protections.limit.world.unlimited")
        && cities.values().stream()
                .filter(
                    c ->
                        c.isOwner(player.getUniqueId())
                            && c.world.equals(block.getWorld().getUID()))
                .count()
            >= wlimit) throw new IllegalArgumentException("Límite de ciudades en este mundo");
    var level = type.levels().firstEntry().getValue();
    Cuboid b = bounds(block.getWorld(), block.getX(), block.getZ(), level.radius());
    if (index.overlaps(b, null))
      throw new IllegalArgumentException("La ciudad se superpone con otra protección");
    if (!block
            .getWorld()
            .getWorldBorder()
            .isInside(new Location(block.getWorld(), b.minX(), block.getY(), b.minZ()))
        || !block
            .getWorld()
            .getWorldBorder()
            .isInside(new Location(block.getWorld(), b.maxX(), block.getY(), b.maxZ())))
      throw new IllegalArgumentException("Fuera del límite del mundo");
    City c = new City(item.token());
    c.owner = player.getUniqueId();
    c.type = type.id();
    c.name = player.getName() + " - " + type.id();
    c.level = level.number();
    c.world = block.getWorld().getUID();
    c.x = block.getX();
    c.y = block.getY();
    c.z = block.getZ();
    c.bounds = b;
    c.flags.putAll(p.catalog().defaultFlags);
    c.flags.putAll(ConfigCatalog.rules(type.yaml().getConfigurationSection("flags")));
    return c;
  }

  public void create(Player player, Block block, Items.Identity item) {
    City c = draftCreation(player, block, item);
    var type = p.catalog().types.get(item.id());
    put(c);
    busy.add(c.id);
    p.repository()
        .commit(
            c.id,
            -1,
            CityCodec.encode(c),
            List.of(new JdbcRepository.TokenMove(item.token(), "ITEM", "CORE:" + c.id)),
            List.of())
        .whenComplete(
            (v, e) ->
                p.main(
                    () -> {
                      busy.remove(c.id);
                      if (e != null) {
                        cities.remove(c.id);
                        index.remove(c.id);
                        block.setType(Material.AIR);
                        p.failure(e);
                        p.delivery().queue(player.getUniqueId(), coreItem(type, item.token()));
                        p.message(player, "operation-failed");
                      } else {
                        changed(c);
                        p.visual().feedback(player, "created", Map.of("city", c.name));
                      }
                    }));
  }

  public ItemStack coreItem(ConfigCatalog.CityType type, UUID token) {
    return p.items()
        .signed(
            p.items()
                .display(
                    type.yaml().getConfigurationSection("item"),
                    type.material(),
                    Map.of("type", type.id())),
            "core",
            type.id(),
            token);
  }

  public CompletableFuture<Void> mint(Player target, String kind, String id, int count) {
    if (count < 1 || count > 64) throw new IllegalArgumentException("Cantidad: 1–64");
    List<JdbcRepository.TokenMint> tokens = new ArrayList<>();
    List<JdbcRepository.Delivery> deliveries = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      UUID token = UUID.randomUUID();
      ItemStack item;
      if (kind.equals("core")) {
        var t = p.catalog().types.get(id);
        if (t == null) throw new IllegalArgumentException("Tipo inexistente");
        item = coreItem(t, token);
      } else {
        var d = p.catalog().crystals.get(id);
        if (d == null) throw new IllegalArgumentException("Cristal inexistente");
        item = crystalItem(d, token);
      }
      tokens.add(new JdbcRepository.TokenMint(token, kind + ":" + id));
      deliveries.add(p.delivery().delivery(target.getUniqueId(), item));
    }
    return p.repository()
        .mintDeliveries(tokens, deliveries)
        .whenComplete(
            (v, e) ->
                p.main(
                    () -> {
                      if (e != null) p.failure(e);
                      else p.delivery().drain(target);
                    }));
  }

  public ItemStack crystalItem(ConfigCatalog.Crystal d, UUID token) {
    return p.items()
        .signed(
            p.items()
                .display(
                    d.yaml().getConfigurationSection("item"),
                    Material.AMETHYST_SHARD,
                    Map.of("required-level", d.requiredLevel())),
            "crystal",
            d.id(),
            token);
  }

  public void update(Player player, City original, Consumer<City> edit, Runnable done) {
    transact(player, original, edit, List.of(), List.of(), null, done);
  }

  public void transact(
      Player player,
      City original,
      Consumer<City> edit,
      List<JdbcRepository.TokenMove> moves,
      List<JdbcRepository.Delivery> delivery,
      EconomyService.Receipt receipt,
      Runnable done) {
    if (original == null || get(original.id) != original || !busy.add(original.id)) {
      if (receipt != null) p.economy().refund(receipt);
      throw new IllegalStateException("La ciudad está procesando otra operación");
    }
    commitLocked(player, original, edit, moves, delivery, receipt, done);
  }

  private void commitLocked(
      Player player,
      City original,
      Consumer<City> edit,
      List<JdbcRepository.TokenMove> moves,
      List<JdbcRepository.Delivery> delivery,
      EconomyService.Receipt receipt,
      Runnable done) {
    commitLocked(player, original, edit, moves, delivery, List.of(), receipt, done);
  }

  private void commitLocked(
      Player player,
      City original,
      Consumer<City> edit,
      List<JdbcRepository.TokenMove> moves,
      List<JdbcRepository.Delivery> delivery,
      List<JdbcRepository.MoneyDelivery> money,
      EconomyService.Receipt receipt,
      Runnable done) {
    City next;
    try {
      next = CityCodec.copy(original);
      edit.accept(next);
      next.revision++;
      next.lastActive = System.currentTimeMillis();
    } catch (RuntimeException e) {
      busy.remove(original.id);
      if (receipt != null) p.economy().refund(receipt);
      throw e;
    }
    p.repository()
        .commit(original.id, original.revision, CityCodec.encode(next), moves, delivery, money)
        .whenComplete(
            (v, error) ->
                p.main(
                    () -> {
                      busy.remove(original.id);
                      if (error != null) {
                        if (receipt != null) p.economy().refund(receipt);
                        p.failure(error);
                        if (player != null) p.message(player, "operation-failed");
                      } else {
                        put(next);
                        changed(next);
                        if (done != null) done.run();
                        for (var d : delivery) p.delivery().drain(Bukkit.getPlayer(d.player()));
                        if (!money.isEmpty()) p.moneyRecovery().drain();
                      }
                    }));
  }

  public void paid(
      Player player, City city, ConfigurationSection cost, Consumer<City> edit, Runnable done) {
    paid(player, city, () -> p.economy().charge(player, cost), edit, done);
  }

  public void paid(
      Player player,
      City city,
      Supplier<CompletableFuture<EconomyService.Receipt>> charge,
      Consumer<City> edit,
      Runnable done) {
    if (city == null || get(city.id) != city || !busy.add(city.id))
      throw new IllegalStateException("Operación pendiente");
    charge
        .get()
        .whenComplete(
            (receipt, error) ->
                p.main(
                    () -> {
                      if (error != null) {
                        busy.remove(city.id);
                        p.message(player, "insufficient-funds");
                        return;
                      }
                      try {
                        commitLocked(player, city, edit, List.of(), List.of(), receipt, done);
                      } catch (RuntimeException e) {
                        p.message(player, e.getMessage());
                      }
                    }));
  }

  public void paidTransfer(
      Player player,
      City city,
      String currency,
      double price,
      UUID payee,
      Consumer<City> edit,
      Runnable done) {
    if (city == null || get(city.id) != city || !busy.add(city.id))
      throw new IllegalStateException("Operación pendiente");
    var payment = p.moneyRecovery().payment(payee, currency, price);
    p.economy()
        .charge(player, currency, price)
        .whenComplete(
            (receipt, error) ->
                p.main(
                    () -> {
                      if (error != null) {
                        busy.remove(city.id);
                        p.message(player, "insufficient-funds");
                        return;
                      }
                      try {
                        commitLocked(
                            player,
                            city,
                            edit,
                            List.of(),
                            List.of(),
                            List.of(payment),
                            receipt,
                            done);
                      } catch (RuntimeException e) {
                        p.message(player, e.getMessage());
                      }
                    }));
  }

  public void upgrade(Player player, City city, boolean free) {
    if (!manager(player, city)
        && !(city.isMember(player.getUniqueId())
            && p.catalog().settings.getBoolean("members.can-upgrade")))
      throw new IllegalArgumentException("Solo el propietario puede mejorar");
    var type = p.catalog().types.get(city.type);
    var next = type.levels().higherEntry(city.level);
    if (next == null) throw new IllegalArgumentException("Nivel máximo alcanzado");
    World w = Bukkit.getWorld(city.world);
    if (w == null) throw new IllegalStateException("Mundo no disponible");
    Cuboid b = bounds(w, city.x, city.z, next.getValue().radius());
    if (index.overlaps(b, city.id))
      throw new IllegalArgumentException("El nuevo radio se superpone con otra ciudad");
    if (!b.contains(city.bounds) && city.subzones.stream().anyMatch(s -> !b.contains(s.bounds)))
      throw new IllegalArgumentException("La mejora dejaría subzonas fuera de la ciudad");
    if (next.getValue().maxMembers() < city.members.size()
        || next.getValue().crystalSlots()
            < city.crystals.keySet().stream().max(Integer::compareTo).orElse(-1) + 1
        || next.getValue().chestRows() * 9
            < city.storage.keySet().stream().max(Integer::compareTo).orElse(-1) + 1)
      throw new IllegalArgumentException(
          "El nivel objetivo no contiene los miembros, cristales o inventario actuales");
    Consumer<City> edit =
        c -> {
          c.level = next.getKey();
          c.bounds = b;
        };
    Runnable done =
        () ->
            p.visual()
                .feedback(
                    player,
                    "upgraded",
                    Map.of("old-level", city.level, "new-level", next.getKey()));
    if (free) update(player, city, edit, done);
    else
      paid(
          player, city, next.getValue().yaml().getConfigurationSection("upgrade-cost"), edit, done);
  }

  public void install(
      Player player, City city, int slot, ItemStack source, Consumer<ItemStack> take) {
    if (!manager(player, city)) throw new IllegalArgumentException("Sin permiso para instalar");
    if (slot < 0 || slot >= level(city).crystalSlots() || city.crystals.containsKey(slot))
      throw new IllegalArgumentException("Slot bloqueado u ocupado");
    Items.Identity identity = p.items().identity(source);
    if (identity == null || !identity.kind().equals("crystal") || source.getAmount() != 1)
      throw new IllegalArgumentException("Selecciona un cristal válido");
    var def = p.catalog().crystals.get(identity.id());
    if (def == null || city.level < def.requiredLevel())
      throw new IllegalArgumentException("Nivel insuficiente");
    for (var installed : city.crystals.values()) {
      var d = p.catalog().crystals.get(installed.id());
      if (d != null && (def.incompatible().contains(d.id()) || d.incompatible().contains(def.id())))
        throw new IllegalArgumentException("Cristal incompatible: " + d.id());
    }
    if (busy(city.id)) throw new IllegalArgumentException("Operación pendiente");
    ItemStack reserved = source.clone();
    take.accept(null);
    var receipt = new EconomyService.Receipt(player.getUniqueId());
    receipt.items.add(reserved);
    try {
      transact(
          player,
          city,
          c -> c.crystals.put(slot, new City.InstalledCrystal(def.id(), identity.token())),
          List.of(
              new JdbcRepository.TokenMove(
                  identity.token(), "ITEM", "CITY:" + city.id + ":" + slot)),
          List.of(),
          receipt,
          () ->
              Text.sound(
                  player,
                  p.catalog()
                      .settings
                      .getString("crystals.install-sound", "BLOCK_AMETHYST_BLOCK_CHIME")));
    } catch (RuntimeException e) {
      throw e;
    }
  }

  public void removeCrystal(Player player, City city, int slot, boolean steal) {
    var installed = city.crystals.get(slot);
    if (installed == null) throw new IllegalArgumentException("Slot vacío");
    var raid = p.events().raid(city);
    if (steal) {
      if (!p.events().canSteal(player, city))
        throw new IllegalArgumentException("No puedes robar cristales ahora");
    } else if (!manager(player, city)) throw new IllegalArgumentException("Sin permiso");
    var d = p.catalog().crystals.get(installed.id());
    if (d == null) throw new IllegalArgumentException("Definición no disponible; restaura el YAML");
    boolean
        remove = !steal || raid.definition.yaml().getBoolean("crystals.remove-after-steal", true),
        give = !steal || raid.definition.yaml().getBoolean("crystals.drop-to-attacker", true);
    if (!remove) {
      String key = raid.id() + ":" + raid.started + ":" + slot;
      if (city.stealReceipts.contains(key))
        throw new IllegalArgumentException("Este slot ya se reclamó durante este evento");
      UUID token = UUID.randomUUID();
      p.repository()
          .mint(token, "crystal:" + d.id())
          .whenComplete(
              (v, e) ->
                  p.main(
                      () -> {
                        if (e != null) {
                          p.failure(e);
                          return;
                        }
                        try {
                          City fresh = get(city.id);
                          if (fresh == null
                              || !p.events().canSteal(player, fresh)
                              || fresh.stealReceipts.contains(key)
                              || !installed.equals(fresh.crystals.get(slot))
                              || p.events().raid(fresh) != raid) return;
                          var delivery =
                              give
                                  ? List.of(
                                      p.delivery()
                                          .delivery(player.getUniqueId(), crystalItem(d, token)))
                                  : List.<JdbcRepository.Delivery>of();
                          transact(
                              player,
                              fresh,
                              n -> {
                                n.stealReceipts.add(key);
                                while (n.stealReceipts.size() > 4096)
                                  n.stealReceipts.remove(n.stealReceipts.iterator().next());
                              },
                              List.of(),
                              delivery,
                              null,
                              null);
                        } catch (RuntimeException error) {
                          p.message(player, error.getMessage());
                        }
                      }));
      return;
    }
    var delivery =
        give
            ? List.of(
                p.delivery().delivery(player.getUniqueId(), crystalItem(d, installed.token())))
            : List.<JdbcRepository.Delivery>of();
    transact(
        player,
        city,
        c -> c.crystals.remove(slot),
        List.of(
            new JdbcRepository.TokenMove(
                installed.token(), "CITY:" + city.id + ":" + slot, give ? "ITEM" : "DELETED")),
        delivery,
        null,
        () ->
            Text.sound(
                player,
                p.catalog()
                    .settings
                    .getString("crystals.remove-sound", "BLOCK_AMETHYST_BLOCK_BREAK")));
  }

  public void delete(Player player, City city) {
    if (!manager(player, city)) throw new IllegalArgumentException("Sin permiso");
    if (get(city.id) != city)
      throw new IllegalArgumentException("La ciudad ha cambiado; abre el menú otra vez");
    var type = p.catalog().types.get(city.type);
    if (type == null)
      throw new IllegalArgumentException("Restaura el tipo de núcleo antes de eliminar");
    if (!busy.add(city.id)) throw new IllegalArgumentException("Operación pendiente");
    List<JdbcRepository.Delivery> deliveries = new ArrayList<>();
    List<JdbcRepository.TokenMove> moves = new ArrayList<>();
    deliveries.add(p.delivery().delivery(city.owner, coreItem(type, city.id)));
    for (var entry : city.crystals.entrySet()) {
      var d = p.catalog().crystals.get(entry.getValue().id());
      if (d == null) {
        busy.remove(city.id);
        throw new IllegalArgumentException(
            "Restaura las definiciones de cristales antes de eliminar");
      }
      deliveries.add(p.delivery().delivery(city.owner, crystalItem(d, entry.getValue().token())));
      moves.add(
          new JdbcRepository.TokenMove(
              entry.getValue().token(), "CITY:" + city.id + ":" + entry.getKey(), "ITEM"));
    }
    for (ItemStack i : city.storage.values()) deliveries.add(p.delivery().delivery(city.owner, i));
    moves.add(new JdbcRepository.TokenMove(city.id, "CORE:" + city.id, "ITEM"));
    p.repository()
        .commit(city.id, city.revision, null, moves, deliveries)
        .whenComplete(
            (v, e) ->
                p.main(
                    () -> {
                      busy.remove(city.id);
                      if (e != null) {
                        p.failure(e);
                        p.message(player, "operation-failed");
                        return;
                      }
                      cities.remove(city.id);
                      index.remove(city.id);
                      World w = Bukkit.getWorld(city.world);
                      // Deletion can be managed from outside the core's loaded chunk.
                      // Load its block so a committed deletion also removes the physical core.
                      if (w != null) w.getBlockAt(city.x, city.y, city.z).setType(Material.AIR);
                      p.visual().remove(city.id);
                      p.effects().refreshAll();
                      p.gui().cityChanged(city.id);
                      p.delivery()
                          .drain(
                              city.owner.equals(player.getUniqueId())
                                  ? player
                                  : Bukkit.getPlayer(city.owner));
                      p.message(player, "city-deleted");
                    }));
  }

  private void changed(City c) {
    p.visual().refresh(c);
    p.effects().refreshCity(c.id);
    p.gui().cityChanged(c.id);
    p.apiEvent(c.id);
  }

  public Map<String, Object> placeholders(City c) {
    var l = level(c);
    Map<String, Object> v = new LinkedHashMap<>();
    v.put("city", c.name);
    v.put("tag", c.tag);
    v.put("level", c.level);
    v.put("max-level", p.catalog().types.get(c.type).levels().lastKey());
    v.put("radius", l.radius());
    v.put("members", c.members.size());
    v.put("max-members", l.maxMembers());
    v.put("storage-rows", l.chestRows());
    v.put("crystal-slots", l.crystalSlots());
    v.put("installed-crystals", c.crystals.size());
    v.put("raid-hits", l.raidHits());
    v.put("subzones", c.subzones.size());
    v.put("max-subzones", l.maxSubzones());
    v.put("max-subzone-blocks", l.maxSubzoneBlocks());
    v.put(
        "owner",
        Optional.ofNullable(Bukkit.getOfflinePlayer(c.owner).getName()).orElse(c.owner.toString()));
    v.put("shield-remaining", Text.remaining(c.shieldUntil - System.currentTimeMillis()));
    v.put(
        "world",
        Optional.ofNullable(Bukkit.getWorld(c.world))
            .map(World::getName)
            .orElse(c.world.toString()));
    v.put("x", c.x);
    v.put("y", c.y);
    v.put("z", c.z);
    return v;
  }
}
