package dev.marco035xd.terranova.config;

import dev.marco035xd.terranova.model.*;
import dev.marco035xd.terranova.util.Text;
import java.io.*;
import java.util.*;
import org.bukkit.*;
import org.bukkit.configuration.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

public final class ConfigCatalog {
  public record Level(
      int number,
      int radius,
      int maxMembers,
      int chestRows,
      int crystalSlots,
      int raidHits,
      int maxSubzones,
      long maxSubzoneBlocks,
      boolean rename,
      ConfigurationSection yaml) {}

  public record CityType(
      String id,
      Material material,
      NavigableMap<Integer, Level> levels,
      ConfigurationSection yaml) {
    public Level level(int n) {
      Level l = levels.get(n);
      if (l == null) throw new IllegalArgumentException("Nivel no definido: " + n);
      return l;
    }
  }

  public enum Activation {
    ALWAYS,
    RAID_ONLY,
    NON_RAID
  }

  public record Crystal(
      String id,
      int requiredLevel,
      Activation activation,
      Set<String> incompatible,
      ConfigurationSection yaml) {}

  public record EventDef(
      String id, String type, int priority, long duration, ConfigurationSection yaml) {}

  public record Shield(String id, long duration, int requiredLevel, ConfigurationSection yaml) {}

  public final Map<String, CityType> types = new LinkedHashMap<>();
  public final Map<String, Crystal> crystals = new LinkedHashMap<>();
  public final Map<String, EventDef> events = new LinkedHashMap<>();
  public final Map<String, Shield> shields = new LinkedHashMap<>();
  public final Map<String, FlagRule> defaultFlags = new LinkedHashMap<>();
  public final Map<String, ConfigurationSection> flagDisplays = new LinkedHashMap<>();
  public final YamlConfiguration settings, messages, gui;
  private final JavaPlugin plugin;

  public ConfigCatalog(JavaPlugin p) throws IOException, InvalidConfigurationException {
    plugin = p;
    settings = load(new File(p.getDataFolder(), "config.yml"));
    messages = load(new File(p.getDataFolder(), "messages.yml"));
    gui = load(new File(p.getDataFolder(), "gui.yml"));
    YamlConfiguration f = load(new File(p.getDataFolder(), "flags.yml"));
    defaultFlags.putAll(rules(f.getConfigurationSection("defaults")));
    ConfigurationSection displays = f.getConfigurationSection("flags");
    if (displays != null)
      for (String key : displays.getKeys(false))
        flagDisplays.put(key, displays.getConfigurationSection(key));
    for (YamlConfiguration y : folder("city-types")) {
      String id = id(y);
      NavigableMap<Integer, Level> levels = new TreeMap<>();
      ConfigurationSection root = y.getConfigurationSection("levels");
      if (root == null) throw new IllegalArgumentException(id + ": falta levels");
      for (String key : root.getKeys(false)) {
        int n = Integer.parseInt(key);
        ConfigurationSection l = root.getConfigurationSection(key);
        int radius = l.getInt("radius"),
            rows = l.getInt("chest-rows"),
            slots = l.getInt("crystal-slots");
        if (n < 1
            || radius < 1
            || radius > 1000000
            || rows < 0
            || rows > 10000
            || slots < 0
            || slots > 10000
            || l.getInt("max-members") < 0
            || l.getInt("raid-block-hits", 1) < 1
            || l.getInt("max-subzones") < 0
            || l.getLong("max-subzone-blocks") < 0)
          throw new IllegalArgumentException(id + " nivel " + n + ": valores fuera de rango");
        validateCost(l.getConfigurationSection("upgrade-cost"));
        levels.put(
            n,
            new Level(
                n,
                radius,
                l.getInt("max-members"),
                rows,
                slots,
                l.getInt("raid-block-hits", 1),
                l.getInt("max-subzones"),
                l.getLong("max-subzone-blocks"),
                l.getBoolean("change-name", true),
                l));
      }
      if (levels.isEmpty()) throw new IllegalArgumentException(id + ": sin niveles");
      Material material = material(y.getString("item.material", "BEACON"));
      unique(
          types, id, new CityType(id, material, Collections.unmodifiableNavigableMap(levels), y));
    }
    for (YamlConfiguration y : folder("crystals")) {
      String id = id(y);
      Activation a =
          Activation.valueOf(
              y.getString("activation-mode", y.getBoolean("raid-only") ? "RAID_ONLY" : "ALWAYS")
                  .toUpperCase(Locale.ROOT));
      validateModules(y);
      unique(
          crystals,
          id,
          new Crystal(
              id,
              y.getInt("required-city-level", 1),
              a,
              new HashSet<>(y.getStringList("incompatible-with")),
              y));
    }
    for (YamlConfiguration y : folder("events")) {
      String id = id(y), type = y.getString("type", "RAID").toUpperCase(Locale.ROOT);
      if (!type.matches("[A-Z][A-Z0-9_]{0,63}"))
        throw new IllegalArgumentException(id + ": tipo de evento inválido");
      rules(y.getConfigurationSection("forced-flags"));
      validateModules(y);
      org.bukkit.boss.BarColor.valueOf(y.getString("bossbar.color", "RED"));
      dev.marco035xd.terranova.service.EventService.style(
          y.getString("bossbar.overlay", "PROGRESS"));
      String mode = y.getString("breaking.mode", "BLACKLIST");
      if (!Set.of("WHITELIST", "BLACKLIST").contains(mode))
        throw new IllegalArgumentException(id + ": breaking.mode inválido");
      for (String block : y.getStringList("breaking.blocks")) material(block);
      Text.duration(y.getString("breaking.block-hit-expiration", "30s"));
      if (y.getLong("breaking.hit-cooldown-millis", 250) < 1)
        throw new IllegalArgumentException(id + ": cooldown debe ser positivo");
      unique(
          events,
          id,
          new EventDef(
              id, type, y.getInt("priority"), Text.duration(y.getString("duration", "30m")), y));
    }
    for (YamlConfiguration y : folder("shields")) {
      String id = id(y);
      validateCost(y.getConfigurationSection("cost"));
      unique(
          shields,
          id,
          new Shield(
              id,
              Text.duration(y.getString("duration", "1h")),
              y.getInt("required-city-level", 1),
              y));
    }
    if (types.isEmpty()) throw new IllegalArgumentException("Configura al menos un tipo de ciudad");
    Text.money(settings.getDouble("subzones.price-per-block", 2));
  }

  private static <T> void unique(Map<String, T> m, String id, T v) {
    if (m.putIfAbsent(id, v) != null) throw new IllegalArgumentException("ID duplicado: " + id);
  }

  private List<YamlConfiguration> folder(String name)
      throws IOException, InvalidConfigurationException {
    File[] files = new File(plugin.getDataFolder(), name).listFiles((d, n) -> n.endsWith(".yml"));
    if (files == null) return List.of();
    Arrays.sort(files, Comparator.comparing(File::getName));
    List<YamlConfiguration> list = new ArrayList<>();
    for (File f : files) {
      YamlConfiguration y = load(f);
      if (y.getBoolean("enabled", true)) list.add(y);
    }
    return list;
  }

  private static YamlConfiguration load(File f) throws IOException, InvalidConfigurationException {
    YamlConfiguration y = new YamlConfiguration();
    y.load(f);
    return y;
  }

  private static String id(ConfigurationSection y) {
    String s = y.getString("id", "");
    if (!s.matches("[a-z0-9][a-z0-9_-]{0,63}"))
      throw new IllegalArgumentException("ID inválido: " + s);
    return s;
  }

  public static Material material(String s) {
    Material m = Material.matchMaterial(s);
    if (m == null) throw new IllegalArgumentException("Material inválido: " + s);
    return m;
  }

  public static Map<String, FlagRule> rules(ConfigurationSection y) {
    Map<String, FlagRule> r = new LinkedHashMap<>();
    if (y == null) return r;
    for (String k : y.getKeys(false)) {
      String v = y.getString(k + ".state", "DENY").toUpperCase(Locale.ROOT);
      if (!v.equals("ALLOW") && !v.equals("DENY"))
        throw new IllegalArgumentException("Estado inválido " + k);
      r.put(
          k,
          new FlagRule(
              v.equals("ALLOW"), Audience.parse(y.getString(k + ".audience", "NON_MEMBERS"))));
    }
    return r;
  }

  private static void validateCost(ConfigurationSection s) {
    if (s == null) return;
    Text.money(s.getDouble("vault"));
    ConfigurationSection ee = s.getConfigurationSection("excellent-economy");
    if (ee != null) for (String k : ee.getKeys(false)) Text.money(ee.getDouble(k));
    for (Map<?, ?> row : s.getMapList("items")) {
      material(String.valueOf(row.get("material")));
      Object amount = row.get("amount");
      if (!(amount instanceof Number n) || n.intValue() < 1)
        throw new IllegalArgumentException("Cantidad inválida en coste");
    }
  }

  private static void validateModules(ConfigurationSection y) {
    for (String path :
        List.of(
            "effects.potion-effects",
            "blocked-items",
            "advanced-enchantments",
            "aura-skills",
            "effects.flight",
            "effects.raid-resistance",
            "effects.crop-growth")) {
      ConfigurationSection s = y.getConfigurationSection(path);
      if (s != null)
        Audience.parse(s.getString("audience", y.getString("effects.audience", "MEMBERS")));
    }
    for (String s : y.getStringList("blocked-items.materials")) material(s);
    for (Map<?, ?> r : y.getMapList("effects.potion-effects.list")) {
      if (!(r.get("type") instanceof String)
          || !(r.get("level") instanceof Number n)
          || n.intValue() < 1
          || n.intValue() > 256) throw new IllegalArgumentException("Efecto de poción inválido");
    }
    ConfigurationSection aura = y.getConfigurationSection("aura-skills");
    if (aura != null) {
      String mode = aura.getString("stacking-mode", "MULTIPLY");
      if (!Set.of("MULTIPLY", "HIGHEST", "ADD").contains(mode))
        throw new IllegalArgumentException("Stacking inválido");
      for (Map<?, ?> r : aura.getMapList("xp-multipliers")) {
        Object mult = r.get("multiplier");
        if (!(mult instanceof Number n)
            || !Double.isFinite(n.doubleValue())
            || n.doubleValue() < 0
            || n.doubleValue() > 1000) throw new IllegalArgumentException("Multiplicador inválido");
      }
      double mana = aura.getDouble("mana-regeneration.multiplier", 1);
      if (!Double.isFinite(mana) || mana < 0 || mana > 1000)
        throw new IllegalArgumentException("Mana inválido");
    }
  }

  public boolean worldEnabled(World w) {
    List<String> names = settings.getStringList("enabled-worlds");
    return names.isEmpty() || names.contains(w.getName());
  }
}
