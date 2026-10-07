package dev.marco035xd.terranova.gui;

import dev.marco035xd.terranova.TerranovaPlugin;
import dev.marco035xd.terranova.config.ConfigCatalog;
import dev.marco035xd.terranova.hook.EconomyService;
import dev.marco035xd.terranova.model.*;
import dev.marco035xd.terranova.service.SubzoneService;
import dev.marco035xd.terranova.storage.*;
import dev.marco035xd.terranova.util.*;
import java.util.*;
import java.util.function.*;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.SkullMeta;

/** Virtual menus. No top inventory contents are trusted as persisted items. */
public final class GuiService implements Listener {
  public enum Screen {
    MAIN,
    LEVELS,
    CRYSTALS,
    FLAGS,
    STORAGE,
    MEMBERS,
    USERFLAGS,
    SUBZONES,
    SUBZONE,
    SHIELDS,
    CITIES,
    CURRENCIES,
    CONFIRM
  }

  public static final class Menu implements InventoryHolder {
    public final UUID viewer, city;
    public final Screen screen;
    public final int page;
    public UUID subzone, user;
    public final Map<Integer, BiConsumer<Player, InventoryClickEvent>> actions = new HashMap<>();
    public final Map<Integer, Integer> logical = new HashMap<>();
    public Inventory inventory;

    Menu(Player p, City city, Screen s, int page) {
      viewer = p.getUniqueId();
      this.city = city == null ? null : city.id;
      screen = s;
      this.page = page;
    }

    public Inventory getInventory() {
      return inventory;
    }
  }

  private record Input(long expires, Consumer<String> handler) {}

  private final TerranovaPlugin p;
  private final Map<UUID, Input> inputs = new java.util.concurrent.ConcurrentHashMap<>();

  public GuiService(TerranovaPlugin p) {
    this.p = p;
  }

  private String title(String key, String fallback, City c) {
    return Text.color(
        Text.replace(
            p.catalog().gui.getString(key + ".title", fallback),
            c == null ? Map.of() : p.cities().placeholders(c)));
  }

  public void open(Player player, City city, Screen screen, int page) {
    open(player, city, screen, page, null, null);
  }

  public void open(Player player, City city, Screen screen, int page, UUID subzone, UUID user) {
    String permission =
        switch (screen) {
          case MAIN, CITIES, MEMBERS -> "menu";
          case LEVELS -> "upgrade";
          case CRYSTALS -> "crystals";
          case STORAGE -> "storage";
          case FLAGS -> subzone == null ? "flags" : "subzone.flags";
          case USERFLAGS -> "userflags";
          case SUBZONE, SUBZONES, CURRENCIES -> "subzone.menu";
          case SHIELDS -> "shield";
          default -> "menu";
        };
    if (!player.hasPermission("terranova.admin")
        && !player.hasPermission("terranova.command." + permission))
      throw new IllegalArgumentException("Sin permiso para abrir este panel");
    if (screen != Screen.CITIES && city == null)
      throw new IllegalArgumentException("No hay ciudad");
    Menu m = new Menu(player, city, screen, Math.max(0, page));
    m.subzone = subzone;
    m.user = user;
    String name = screen.name().toLowerCase(Locale.ROOT);
    m.inventory = Bukkit.createInventory(m, 54, title(name, "&8Terranova &8│ " + name, city));
    ItemStack filler =
        p.items()
            .display(
                p.catalog().gui.getConfigurationSection("decoration"),
                Material.GRAY_STAINED_GLASS_PANE,
                Map.of());
    for (int slot = 0; slot < 54; slot++) m.inventory.setItem(slot, filler);
    button(m, 49, p.items().simple(Material.BARRIER, "&cCerrar"), (a, e) -> a.closeInventory());
    if (screen != Screen.MAIN && screen != Screen.CITIES)
      button(
          m,
          45,
          p.items().simple(Material.ARROW, "&eVolver"),
          (a, e) -> open(a, p.cities().get(city.id), Screen.MAIN, 0));
    switch (screen) {
      case MAIN -> main(player, city, m);
      case LEVELS -> levels(player, city, m);
      case CRYSTALS -> crystals(player, city, m);
      case FLAGS, USERFLAGS -> flags(player, city, m);
      case STORAGE -> storage(player, city, m);
      case MEMBERS -> members(player, city, m);
      case SUBZONES -> subzones(player, city, m);
      case SUBZONE -> subzone(player, city, m);
      case SHIELDS -> shields(player, city, m);
      case CITIES -> cities(player, m);
      case CURRENCIES -> currencies(player, city, m);
      default -> {}
    }
    player.openInventory(m.inventory);
  }

  private void button(Menu m, int slot, ItemStack i, BiConsumer<Player, InventoryClickEvent> a) {
    m.inventory.setItem(slot, i);
    m.actions.put(slot, a);
  }

  private void icon(
      Menu m,
      City c,
      String key,
      int defaultSlot,
      Material mat,
      String name,
      BiConsumer<Player, InventoryClickEvent> a) {
    var y = p.catalog().gui.getConfigurationSection("main.buttons." + key);
    int slot = y == null ? defaultSlot : y.getInt("slot", defaultSlot);
    if (slot < 0 || slot >= 54) throw new IllegalArgumentException("Slot de GUI inválido: " + key);
    ItemStack item =
        y == null
            ? p.items().simple(mat, name)
            : p.items().display(y, mat, p.cities().placeholders(c));
    button(m, slot, item, a);
  }

  private City require(Player player, Menu m) {
    City c = p.cities().get(m.city);
    if (c == null) throw new IllegalArgumentException("Ciudad eliminada");
    return c;
  }

  private boolean storageAccess(Player player, City c) {
    World w = Bukkit.getWorld(c.world);
    return w != null
        && p.decisions().decide(player, new Location(w, c.x, c.y, c.z), "chest-access").allowed();
  }

  private void main(Player player, City c, Menu m) {
    icon(m, c, "info", 4, Material.BEACON, "&#66E6B5" + c.name, (a, e) -> {});
    icon(
        m,
        c,
        "levels",
        20,
        Material.EXPERIENCE_BOTTLE,
        "&aMejoras",
        (a, e) -> open(a, require(a, m), Screen.LEVELS, 0));
    icon(
        m,
        c,
        "members",
        21,
        Material.PLAYER_HEAD,
        "&bMiembros",
        (a, e) -> open(a, require(a, m), Screen.MEMBERS, 0));
    icon(
        m,
        c,
        "flags",
        22,
        Material.COMPARATOR,
        "&ePermisos y flags",
        (a, e) -> open(a, require(a, m), Screen.FLAGS, 0));
    icon(
        m,
        c,
        "crystals",
        23,
        Material.AMETHYST_SHARD,
        "&dCristales",
        (a, e) -> open(a, require(a, m), Screen.CRYSTALS, 0));
    icon(
        m,
        c,
        "storage",
        24,
        Material.CHEST,
        "&6Almacenamiento",
        (a, e) -> {
          City current = require(a, m);
          if (!storageAccess(a, current))
            throw new IllegalArgumentException("Sin acceso al almacén comunitario");
          open(a, current, Screen.STORAGE, 0);
        });
    icon(
        m,
        c,
        "subzones",
        30,
        Material.BLAZE_ROD,
        "&bSubzonas",
        (a, e) -> open(a, require(a, m), Screen.SUBZONES, 0));
    icon(
        m,
        c,
        "shields",
        31,
        Material.SHIELD,
        "&aEscudos",
        (a, e) -> open(a, require(a, m), Screen.SHIELDS, 0));
    icon(
        m,
        c,
        "border",
        32,
        Material.ENDER_EYE,
        "&eVer borde",
        (a, e) -> {
          p.visual().border(a, require(a, m));
          a.closeInventory();
        });
    icon(
        m,
        c,
        "rename",
        38,
        Material.NAME_TAG,
        "&fCambiar nombre",
        (a, e) -> {
          if (!p.cities().manager(a, c) || !p.cities().level(c).rename())
            throw new IllegalArgumentException("Sin permiso para cambiar nombre");
          input(
              a,
              "Escribe el nombre; cancelar para salir",
              s -> p.commands().rename(a, require(a, m), s, false));
        });
    icon(
        m,
        c,
        "hologram",
        40,
        Material.ARMOR_STAND,
        "&fHolograma",
        (a, e) -> {
          if (!p.cities().manager(a, c)) throw new IllegalArgumentException("Sin permiso");
          p.cities().update(a, require(a, m), n -> n.hologram = !n.hologram, null);
        });
    icon(
        m,
        c,
        "delete",
        42,
        Material.LAVA_BUCKET,
        "&cEliminar ciudad",
        (a, e) ->
            confirm(
                a,
                require(a, m),
                "&cEliminar ciudad y todas sus subzonas",
                () -> p.cities().delete(a, require(a, m))));
  }

  private void page(Menu m, Player player, City c, int count, int perPage) {
    if (m.page > 0)
      button(
          m,
          48,
          p.items().simple(Material.ARROW, "&ePágina anterior"),
          (a, e) -> open(a, requireOrNull(m), m.screen, m.page - 1, m.subzone, m.user));
    if ((m.page + 1) * perPage < count)
      button(
          m,
          50,
          p.items().simple(Material.ARROW, "&ePágina siguiente"),
          (a, e) -> open(a, requireOrNull(m), m.screen, m.page + 1, m.subzone, m.user));
  }

  private City requireOrNull(Menu m) {
    return m.city == null ? null : p.cities().get(m.city);
  }

  private int grid(int i) {
    return 10 + i % 7 + i / 7 * 9;
  }

  private void levels(Player player, City c, Menu m) {
    List<ConfigCatalog.Level> list =
        new ArrayList<>(p.catalog().types.get(c.type).levels().values());
    page(m, player, c, list.size(), 21);
    var current = p.cities().level(c);
    for (int j = 0; j < 21; j++) {
      int i = m.page * 21 + j;
      if (i >= list.size()) break;
      var target = list.get(i);
      Map<String, Object> vars = new LinkedHashMap<>(p.cities().placeholders(c));
      String[] keys = {
        "radius",
        "members",
        "storage-rows",
        "crystal-slots",
        "raid-hits",
        "subzones",
        "subzone-blocks"
      };
      Object[]
          old =
              {
                current.radius(),
                current.maxMembers(),
                current.chestRows(),
                current.crystalSlots(),
                current.raidHits(),
                current.maxSubzones(),
                current.maxSubzoneBlocks()
              },
          next =
              {
                target.radius(),
                target.maxMembers(),
                target.chestRows(),
                target.crystalSlots(),
                target.raidHits(),
                target.maxSubzones(),
                target.maxSubzoneBlocks()
              };
      for (int k = 0; k < keys.length; k++) {
        vars.put("current-" + keys[k], old[k]);
        vars.put("target-" + keys[k], next[k]);
      }
      vars.put("target-level", target.number());
      ItemStack item =
          p.items()
              .display(
                  target.yaml().getConfigurationSection("gui-display"),
                  target.number() <= c.level ? Material.LIME_DYE : Material.EXPERIENCE_BOTTLE,
                  vars);
      button(
          m,
          grid(j),
          item,
          (a, e) -> {
            City fresh = require(a, m);
            Integer nextLevel = p.catalog().types.get(fresh.type).levels().higherKey(fresh.level);
            if (!Objects.equals(nextLevel, target.number()))
              throw new IllegalArgumentException("Mejora al siguiente nivel en orden");
            confirm(
                a,
                fresh,
                "&aConfirmar mejora al nivel " + target.number(),
                () -> p.cities().upgrade(a, require(a, m), false));
          });
    }
  }

  private void crystals(Player player, City c, Menu m) {
    int max =
        p.catalog().types.get(c.type).levels().values().stream()
            .mapToInt(ConfigCatalog.Level::crystalSlots)
            .max()
            .orElse(0);
    int available = p.cities().level(c).crystalSlots();
    page(m, player, c, max, 21);
    for (int j = 0; j < 21; j++) {
      int slot = m.page * 21 + j;
      if (slot >= max) break;
      int physical = grid(j);
      m.logical.put(physical, slot);
      var installed = c.crystals.get(slot);
      ItemStack item;
      if (installed != null) {
        var def = p.catalog().crystals.get(installed.id());
        item =
            def == null
                ? p.items()
                    .simple(Material.BARRIER, "&cDefinición no disponible: " + installed.id())
                : p.cities().crystalItem(def, installed.token());
        if (def != null) {
          var meta = item.getItemMeta();
          List<String> lore =
              new ArrayList<>(Optional.ofNullable(meta.getLore()).orElse(List.of()));
          lore.add(Text.color(" &8│ &7Activación: &f" + def.activation()));
          lore.add(Text.color(" &8│ &7Nivel: &f" + def.requiredLevel()));
          lore.add(
              Text.color(
                  " &8│ &7Estado: "
                      + (c.level < def.requiredLevel()
                          ? "&c🔒 Nivel insuficiente"
                          : p.events().activation(c, def.activation())
                              ? "&a● Activo"
                              : "&7○ Inactivo")));
          for (var h : p.effects().registry().handlers()) {
            var s = def.yaml().getConfigurationSection(h.path());
            if (s != null)
              lore.add(
                  Text.color(" &8│ &7" + h.path() + ": &f" + s.getString("audience", "MEMBERS")));
          }
          for (Map<?, ?> r : def.yaml().getMapList("effects.potion-effects.list"))
            lore.add(Text.color(" &8▪ &f" + r.get("type") + " " + r.get("level")));
          lore.add(Text.color(" &8│ &7Incompatibles: &f" + String.join(", ", def.incompatible())));
          lore.add("");
          lore.add(
              Text.color(
                  p.cities().manager(player, c)
                      ? "&eClic para retirar"
                      : "&cClic para robar durante un raid autorizado"));
          meta.setLore(lore);
          item.setItemMeta(meta);
        }
      } else
        item =
            p.items()
                .display(
                    p.catalog()
                        .gui
                        .getConfigurationSection(
                            slot < available ? "crystals.available" : "crystals.locked"),
                    slot < available
                        ? Material.LIME_STAINED_GLASS_PANE
                        : Material.PINK_STAINED_GLASS_PANE,
                    Map.of("slot", slot + 1));
      button(
          m,
          physical,
          item,
          (a, e) -> {
            City fresh = require(a, m);
            if (fresh.crystals.containsKey(slot)) {
              boolean steal = !p.cities().manager(a, fresh);
              if (steal && !p.events().canSteal(a, fresh))
                throw new IllegalArgumentException("Robo deshabilitado");
              boolean confirmation =
                  steal
                      ? p.events()
                          .raid(fresh)
                          .definition
                          .yaml()
                          .getBoolean("crystals.confirmation", true)
                      : p.catalog().settings.getBoolean("crystals.remove-confirmation", true);
              Runnable run = () -> p.cities().removeCrystal(a, require(a, m), slot, steal);
              if (confirmation)
                confirm(a, fresh, steal ? "&cConfirmar robo" : "&eRetirar cristal", run);
              else run.run();
            } else p.cities().install(a, fresh, slot, e.getCursor(), i -> a.setItemOnCursor(i));
          });
    }
  }

  private void flags(Player player, City c, Menu m) {
    List<String> list = new ArrayList<>(p.catalog().defaultFlags.keySet());
    page(m, player, c, list.size(), 21);
    City.Subzone sub =
        m.subzone == null ? null : p.subzones().find(c, m.subzone.toString(), player.getLocation());
    Map<String, FlagRule> rules = sub == null ? c.flags : sub.flags;
    for (int j = 0; j < 21; j++) {
      int i = m.page * 21 + j;
      if (i >= list.size()) break;
      String flag = list.get(i);
      FlagRule rule = rules.getOrDefault(flag, new FlagRule(false, Audience.NON_MEMBERS));
      boolean userFlag = m.screen == Screen.USERFLAGS;
      Map<String, Boolean> personal =
          (sub == null ? c.individual : sub.individual).getOrDefault(m.user, Map.of());
      boolean allowed = userFlag ? personal.getOrDefault(flag, false) : rule.allow();
      var display = p.catalog().flagDisplays.get(flag);
      ItemStack item =
          display == null
              ? p.items()
                  .simple(
                      Material.COMPARATOR,
                      "&e" + flag,
                      "",
                      " &8│ &7Estado: " + (allowed ? "&aALLOW" : "&cDENY"),
                      " &8│ &7Audiencia: &f" + rule.audience(),
                      "",
                      "&eIzquierdo: estado · Derecho: audiencia")
              : p.items()
                  .display(
                      display.getConfigurationSection(
                          allowed ? "gui-display.enabled" : "gui-display.disabled"),
                      Material.COMPARATOR,
                      Map.of(
                          "status",
                          allowed ? "&aALLOW" : "&cDENY",
                          "audience",
                          rule.audience().name()));
      button(
          m,
          grid(j),
          item,
          (a, e) -> {
            City fresh = require(a, m);
            if (sub == null
                ? !p.cities().manager(a, fresh)
                : !p.subzones()
                    .owner(a, fresh, p.subzones().find(fresh, sub.id.toString(), a.getLocation())))
              throw new IllegalArgumentException("Sin permiso");
            p.cities()
                .update(
                    a,
                    fresh,
                    n -> {
                      City.Subzone s =
                          sub == null
                              ? null
                              : p.subzones().find(n, sub.id.toString(), a.getLocation());
                      if (userFlag) {
                        Map<UUID, Map<String, Boolean>> map =
                            s == null ? n.individual : s.individual;
                        map.computeIfAbsent(m.user, k -> new LinkedHashMap<>()).put(flag, !allowed);
                      } else {
                        Map<String, FlagRule> map = s == null ? n.flags : s.flags;
                        Audience aud = rule.audience();
                        if (e.isRightClick())
                          aud = Audience.values()[(aud.ordinal() + 1) % Audience.values().length];
                        map.put(flag, new FlagRule(e.isRightClick() ? allowed : !allowed, aud));
                      }
                    },
                    null);
          });
    }
  }

  private void storage(Player player, City c, Menu m) {
    if (!storageAccess(player, c)) throw new IllegalArgumentException("Sin acceso al almacén");
    int slots = p.cities().level(c).chestRows() * 9;
    page(m, player, c, slots, 45);
    for (int j = 0; j < 45; j++) {
      int slot = m.page * 45 + j;
      if (slot >= slots) continue;
      m.logical.put(j, slot);
      m.inventory.setItem(j, c.storage.get(slot));
      m.actions.put(
          j,
          (a, e) -> {
            City fresh = require(a, m);
            if (!storageAccess(a, fresh)) throw new IllegalArgumentException("Sin acceso");
            if (p.cities().busy(fresh.id))
              throw new IllegalArgumentException("Operación pendiente");
            ItemStack cursor = e.getCursor();
            if (cursor != null && !cursor.getType().isAir()) {
              if (fresh.storage.containsKey(slot))
                throw new IllegalArgumentException("Vacía este slot primero");
              ItemStack item = cursor.clone();
              a.setItemOnCursor(null);
              var receipt = new EconomyService.Receipt(a.getUniqueId());
              receipt.items.add(item);
              p.cities()
                  .transact(
                      a,
                      fresh,
                      n -> n.storage.put(slot, item),
                      List.of(),
                      List.of(),
                      receipt,
                      null);
            } else {
              ItemStack item = fresh.storage.get(slot);
              if (item == null) return;
              p.cities()
                  .transact(
                      a,
                      fresh,
                      n -> n.storage.remove(slot),
                      List.of(),
                      List.of(p.delivery().delivery(a.getUniqueId(), item)),
                      null,
                      null);
            }
          });
    }
  }

  private void members(Player player, City c, Menu m) {
    Set<UUID> users = new LinkedHashSet<>(c.members);
    users.addAll(c.individual.keySet());
    List<UUID> list = new ArrayList<>(users);
    page(m, player, c, list.size(), 21);
    for (int j = 0; j < 21; j++) {
      int i = m.page * 21 + j;
      if (i >= list.size()) break;
      UUID u = list.get(i);
      OfflinePlayer offline = Bukkit.getOfflinePlayer(u);
      ItemStack head =
          p.items()
              .simple(
                  Material.PLAYER_HEAD,
                  "&f" + Optional.ofNullable(offline.getName()).orElse(u.toString()),
                  "",
                  " &8│ &7Rol: "
                      + (c.members.contains(u) ? "&aMiembro" : "&ePermisos individuales"),
                  "",
                  "&eIzquierdo: permisos · Derecho: retirar");
      SkullMeta meta = (SkullMeta) head.getItemMeta();
      meta.setOwningPlayer(offline);
      head.setItemMeta(meta);
      button(
          m,
          grid(j),
          head,
          (a, e) -> {
            if (!p.cities().manager(a, require(a, m)))
              throw new IllegalArgumentException("Sin permiso");
            if (e.isRightClick())
              p.cities()
                  .update(
                      a,
                      require(a, m),
                      n -> {
                        n.members.remove(u);
                        n.individual.remove(u);
                      },
                      null);
            else open(a, require(a, m), Screen.USERFLAGS, 0, null, u);
          });
    }
    button(
        m,
        47,
        p.items().simple(Material.EMERALD, "&aAgregar miembro"),
        (a, e) -> {
          if (!p.cities().manager(a, require(a, m)))
            throw new IllegalArgumentException("Sin permiso");
          input(
              a,
              "Escribe el nombre del jugador",
              s -> p.commands().member(a, require(a, m), s, true));
        });
  }

  private void subzones(Player player, City c, Menu m) {
    page(m, player, c, c.subzones.size(), 21);
    for (int j = 0; j < 21; j++) {
      int i = m.page * 21 + j;
      if (i >= c.subzones.size()) break;
      City.Subzone s = c.subzones.get(i);
      button(
          m,
          grid(j),
          p.items()
              .simple(
                  Material.GRASS_BLOCK,
                  "&b" + s.name,
                  "",
                  " &8│ &7Volumen: &f" + s.bounds.volume(),
                  " &8│ &7En venta: &f" + s.forSale,
                  " &8│ &7Precio: &a" + s.salePrice + " " + s.saleCurrency),
          (a, e) -> open(a, require(a, m), Screen.SUBZONE, 0, s.id, null));
    }
    button(
        m,
        47,
        p.items().simple(Material.BLAZE_ROD, "&aHerramienta de selección"),
        (a, e) -> {
          if (!p.cities().manager(a, c)) throw new IllegalArgumentException("Sin permiso");
          p.delivery().queue(a.getUniqueId(), p.subzones().wand());
        });
    button(
        m,
        51,
        p.items()
            .simple(
                Material.EMERALD_BLOCK,
                "&aCrear selección",
                "",
                "&7Validación: &f"
                    + p.subzones()
                        .validate(
                            c,
                            Optional.ofNullable(p.subzones().selection(player.getUniqueId()))
                                .map(SubzoneService.Selection::box)
                                .orElse(null))),
        (a, e) ->
            input(
                a,
                "Escribe el nombre de la nueva subzona",
                name -> p.subzones().create(a, require(a, m), name)));
  }

  private void subzone(Player player, City c, Menu m) {
    City.Subzone sub = p.subzones().find(c, m.subzone.toString(), player.getLocation());
    if (sub == null) throw new IllegalArgumentException("Subzona eliminada");
    button(
        m,
        20,
        p.items().simple(Material.COMPARATOR, "&eFlags de subzona"),
        (a, e) -> open(a, require(a, m), Screen.FLAGS, 0, sub.id, null));
    button(
        m,
        22,
        p.items()
            .simple(
                Material.EMERALD,
                "&aComprar",
                "",
                " &8│ &7Precio: &f" + sub.salePrice + " " + sub.saleCurrency),
        (a, e) ->
            confirm(
                a,
                require(a, m),
                "&aComprar " + sub.name,
                () ->
                    p.subzones()
                        .buy(
                            a,
                            require(a, m),
                            p.subzones().find(require(a, m), sub.id.toString(), a.getLocation()))));
    button(
        m,
        24,
        p.items().simple(Material.GOLD_INGOT, "&ePoner en venta"),
        (a, e) -> {
          if (!p.subzones().owner(a, require(a, m), sub))
            throw new IllegalArgumentException("Sin permiso");
          open(a, require(a, m), Screen.CURRENCIES, 0, sub.id, null);
        });
    button(
        m,
        31,
        p.items().simple(Material.BARRIER, "&cEliminar subzona"),
        (a, e) ->
            confirm(
                a,
                require(a, m),
                "&cEliminar " + sub.name,
                () -> {
                  City fresh = require(a, m);
                  if (!p.subzones()
                      .owner(
                          a, fresh, p.subzones().find(fresh, sub.id.toString(), a.getLocation())))
                    throw new IllegalArgumentException("Sin permiso");
                  p.cities()
                      .update(a, fresh, n -> n.subzones.removeIf(s -> s.id.equals(sub.id)), null);
                }));
  }

  private void currencies(Player player, City c, Menu m) {
    List<String> list = p.economy().currencies();
    page(m, player, c, list.size(), 21);
    for (int j = 0; j < 21; j++) {
      int i = m.page * 21 + j;
      if (i >= list.size()) break;
      String currency = list.get(i);
      var display =
          p.catalog().settings.getConfigurationSection("currencies." + currency.replace(":", "-"));
      ItemStack item =
          display == null
              ? p.items()
                  .simple(
                      Material.GOLD_INGOT, "&e" + currency, "", "&eClic para elegir esta moneda")
              : p.items().display(display, Material.GOLD_INGOT, Map.of("currency", currency));
      button(
          m,
          grid(j),
          item,
          (a, e) ->
              input(
                  a,
                  "Escribe el precio en " + currency,
                  text -> {
                    double price = Double.parseDouble(text.trim());
                    Text.money(price);
                    City fresh = require(a, m);
                    var sub = p.subzones().find(fresh, m.subzone.toString(), a.getLocation());
                    confirm(
                        a,
                        fresh,
                        "&eVender por " + price + " " + currency,
                        () ->
                            p.subzones()
                                .sell(
                                    a,
                                    require(a, m),
                                    p.subzones()
                                        .find(require(a, m), m.subzone.toString(), a.getLocation()),
                                    price,
                                    currency));
                  }));
    }
    if (list.isEmpty())
      m.inventory.setItem(
          22, p.items().simple(Material.BARRIER, "&cNo hay una economía disponible"));
  }

  private void shields(Player player, City c, Menu m) {
    List<ConfigCatalog.Shield> list = new ArrayList<>(p.catalog().shields.values());
    page(m, player, c, list.size(), 21);
    for (int j = 0; j < 21; j++) {
      int i = m.page * 21 + j;
      if (i >= list.size()) break;
      var def = list.get(i);
      ItemStack item =
          p.items()
              .display(
                  def.yaml().getConfigurationSection("item"),
                  Material.SHIELD,
                  Map.of(
                      "duration",
                      Text.remaining(def.duration()),
                      "required-level",
                      def.requiredLevel(),
                      "remaining",
                      Text.remaining(c.shieldUntil - System.currentTimeMillis())));
      button(
          m,
          grid(j),
          item,
          (a, e) ->
              confirm(
                  a,
                  require(a, m),
                  "&aActivar escudo " + def.id(),
                  () -> {
                    City fresh = require(a, m);
                    if (!p.cities().manager(a, fresh) || fresh.level < def.requiredLevel())
                      throw new IllegalArgumentException("Sin permiso o nivel insuficiente");
                    if (fresh.shieldActive(System.currentTimeMillis()))
                      throw new IllegalArgumentException("Ya hay un escudo activo");
                    p.cities()
                        .paid(
                            a,
                            fresh,
                            def.yaml().getConfigurationSection("cost"),
                            n -> {
                              n.shield = def.id();
                              n.shieldUntil =
                                  Math.addExact(System.currentTimeMillis(), def.duration());
                            },
                            null);
                  }));
    }
    if (c.shieldActive(System.currentTimeMillis()))
      button(
          m,
          4,
          p.items()
              .simple(
                  Material.CLOCK,
                  "&aEscudo activo",
                  "",
                  " &8│ &7Restante: &f"
                      + Text.remaining(c.shieldUntil - System.currentTimeMillis()),
                  "",
                  "&eClic para retirar"),
          (a, e) ->
              confirm(
                  a,
                  require(a, m),
                  "&cRetirar escudo",
                  () -> {
                    if (!p.cities().manager(a, c))
                      throw new IllegalArgumentException("Sin permiso");
                    p.cities()
                        .update(
                            a,
                            require(a, m),
                            n -> {
                              n.shield = "";
                              n.shieldUntil = 0;
                            },
                            null);
                  }));
  }

  private void cities(Player player, Menu m) {
    List<City> list =
        p.cities().all().stream()
            .filter(
                c -> c.isMember(player.getUniqueId()) || player.hasPermission("terranova.admin"))
            .toList();
    page(m, player, null, list.size(), 21);
    for (int j = 0; j < 21; j++) {
      int i = m.page * 21 + j;
      if (i >= list.size()) break;
      City c = list.get(i);
      button(
          m,
          grid(j),
          p.items()
              .simple(
                  Material.BEACON,
                  "&b" + c.name,
                  "",
                  " &8│ &7Nivel: &f" + c.level,
                  " &8│ &7Mundo: &f" + p.cities().placeholders(c).get("world")),
          (a, e) -> open(a, p.cities().get(c.id), Screen.MAIN, 0));
    }
  }

  public void confirm(Player player, City c, String text, Runnable task) {
    Menu m = new Menu(player, c, Screen.CONFIRM, 0);
    m.inventory = Bukkit.createInventory(m, 27, Text.color("&8Confirmación"));
    button(
        m,
        11,
        p.items().simple(Material.LIME_CONCRETE, text, "", "&eConfirmar"),
        (a, e) -> {
          a.closeInventory();
          task.run();
        });
    button(
        m,
        15,
        p.items().simple(Material.RED_CONCRETE, "&cCancelar"),
        (a, e) -> open(a, p.cities().get(c.id), Screen.MAIN, 0));
    player.openInventory(m.inventory);
  }

  public void input(Player player, String prompt, Consumer<String> callback) {
    player.closeInventory();
    inputs.put(player.getUniqueId(), new Input(System.currentTimeMillis() + 60000, callback));
    Text.send(player, "&e" + prompt + " &7(cancelar para salir)");
  }

  @EventHandler(priority = EventPriority.HIGHEST)
  public void chat(AsyncPlayerChatEvent e) {
    Input input;
    synchronized (inputs) {
      input = inputs.get(e.getPlayer().getUniqueId());
    }
    if (input == null) return;
    e.setCancelled(true);
    String message = e.getMessage();
    p.main(
        () -> {
          Input actual = inputs.remove(e.getPlayer().getUniqueId());
          if (actual == null
              || actual.expires < System.currentTimeMillis()
              || message.equalsIgnoreCase("cancelar")) return;
          try {
            actual.handler.accept(message);
          } catch (RuntimeException error) {
            p.message(e.getPlayer(), error.getMessage());
          }
        });
  }

  @EventHandler(priority = EventPriority.HIGHEST)
  public void click(InventoryClickEvent e) {
    if (!(e.getView().getTopInventory().getHolder() instanceof Menu m)
        || !(e.getWhoClicked() instanceof Player player)) return;
    e.setCancelled(true);
    if (!m.viewer.equals(player.getUniqueId())) return;
    if (e.getClick() == ClickType.DOUBLE_CLICK
        || e.isShiftClick()
        || e.getClick() == ClickType.NUMBER_KEY
        || e.getClick() == ClickType.SWAP_OFFHAND
        || e.getClick() == ClickType.DROP
        || e.getClick() == ClickType.CONTROL_DROP) return;
    if (m.city != null && p.cities().busy(m.city)) return;
    if (e.getRawSlot() >= e.getView().getTopInventory().getSize()) {
      if ((m.screen == Screen.CRYSTALS || m.screen == Screen.STORAGE)
          && (e.getClick() == ClickType.LEFT || e.getClick() == ClickType.RIGHT))
        e.setCancelled(false);
      return;
    }
    var action = m.actions.get(e.getRawSlot());
    if (action != null)
      try {
        action.accept(player, e);
      } catch (RuntimeException error) {
        p.message(player, error.getMessage());
        Text.sound(player, "BLOCK_NOTE_BLOCK_BASS");
      }
  }

  @EventHandler(priority = EventPriority.HIGHEST)
  public void drag(InventoryDragEvent e) {
    if (!(e.getView().getTopInventory().getHolder() instanceof Menu m)
        || !(e.getWhoClicked() instanceof Player player)) return;
    e.setCancelled(true);
    if (!m.viewer.equals(player.getUniqueId())) return;
    int top = e.getView().getTopInventory().getSize();
    boolean touchesTop = e.getRawSlots().stream().anyMatch(s -> s < top);
    if (!touchesTop) {
      if (m.city == null || !p.cities().busy(m.city)) e.setCancelled(false);
      return;
    }
    if (m.screen != Screen.CRYSTALS || e.getRawSlots().size() != 1) return;
    int physical = e.getRawSlots().iterator().next();
    Integer slot = m.logical.get(physical);
    if (slot == null) return;
    ItemStack before = e.getOldCursor().clone();
    p.getServer()
        .getScheduler()
        .runTask(
            p,
            () -> {
              if (!player.isOnline()
                  || player.getOpenInventory().getTopInventory().getHolder() != m
                  || !Objects.equals(player.getItemOnCursor(), before)) return;
              try {
                p.cities()
                    .install(
                        player,
                        require(player, m),
                        slot,
                        player.getItemOnCursor(),
                        i -> player.setItemOnCursor(i));
              } catch (RuntimeException error) {
                p.message(player, error.getMessage());
              }
            });
  }

  public void cityChanged(UUID city) {
    for (Player player : Bukkit.getOnlinePlayers())
      if (player.getOpenInventory().getTopInventory().getHolder() instanceof Menu m
          && city.equals(m.city)) {
        City c = p.cities().get(city);
        if (c == null) player.closeInventory();
        else if (m.screen != Screen.CONFIRM)
          try {
            open(player, c, m.screen, m.page, m.subzone, m.user);
          } catch (RuntimeException e) {
            player.closeInventory();
          }
      }
  }

  public void refreshAll() {
    for (Player player : Bukkit.getOnlinePlayers())
      if (player.getOpenInventory().getTopInventory().getHolder() instanceof Menu m
          && m.screen != Screen.CONFIRM
          && m.city != null)
        try {
          open(player, p.cities().get(m.city), m.screen, m.page, m.subzone, m.user);
        } catch (RuntimeException e) {
          player.closeInventory();
        }
  }

  public void forget(UUID u) {
    inputs.remove(u);
  }
}
