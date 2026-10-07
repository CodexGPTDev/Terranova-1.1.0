package dev.marco035xd.terranova.storage;

import dev.marco035xd.terranova.model.*;
import java.util.*;
import org.bukkit.configuration.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

/** Bukkit item serialization happens on the server thread, never on the JDBC worker. */
public final class CityCodec {
  private CityCodec() {}

  public static String encode(City c) {
    YamlConfiguration y = new YamlConfiguration();
    y.set("id", c.id.toString());
    y.set("owner", c.owner.toString());
    y.set("type", c.type);
    y.set("name", c.name);
    y.set("tag", c.tag);
    y.set("level", c.level);
    y.set("world", c.world.toString());
    y.set("x", c.x);
    y.set("y", c.y);
    y.set("z", c.z);
    y.set("revision", c.revision);
    y.set("last-active", c.lastActive);
    y.set("hologram", c.hologram);
    y.set("shield.id", c.shield);
    y.set("shield.until", c.shieldUntil);
    box(y, "bounds", c.bounds);
    members(y, "members", c.members);
    flags(y, "flags", c.flags);
    individual(y, "individual", c.individual);
    y.set("steal-receipts", new ArrayList<>(c.stealReceipts));
    c.crystals.forEach(
        (s, k) -> {
          y.set("crystals." + s + ".id", k.id());
          y.set("crystals." + s + ".token", k.token().toString());
        });
    c.storage.forEach((s, i) -> y.set("storage." + s, i));
    for (City.Subzone s : c.subzones) {
      String p = "subzones." + s.id;
      y.set(p + ".name", s.name);
      y.set(p + ".owner", s.owner.toString());
      box(y, p + ".bounds", s.bounds);
      members(y, p + ".members", s.members);
      flags(y, p + ".flags", s.flags);
      individual(y, p + ".individual", s.individual);
      y.set(p + ".sale-price", s.salePrice);
      y.set(p + ".sale-currency", s.saleCurrency);
      y.set(p + ".for-sale", s.forSale);
    }
    return y.saveToString();
  }

  public static City decode(String raw) {
    try {
      YamlConfiguration y = new YamlConfiguration();
      y.loadFromString(raw);
      City c = new City(UUID.fromString(y.getString("id")));
      c.owner = UUID.fromString(y.getString("owner"));
      c.world = UUID.fromString(y.getString("world"));
      c.type = y.getString("type");
      c.name = y.getString("name");
      c.tag = y.getString("tag", "");
      c.level = y.getInt("level");
      c.x = y.getInt("x");
      c.y = y.getInt("y");
      c.z = y.getInt("z");
      c.bounds = box(y, "bounds", c.world);
      c.revision = y.getLong("revision");
      c.lastActive = y.getLong("last-active");
      c.hologram = y.getBoolean("hologram", true);
      c.shield = y.getString("shield.id", "");
      c.shieldUntil = y.getLong("shield.until");
      c.members.addAll(members(y, "members"));
      c.flags.putAll(ConfigRules(y.getConfigurationSection("flags")));
      readIndividual(y, "individual", c.individual);
      c.stealReceipts.addAll(y.getStringList("steal-receipts"));
      ConfigurationSection crystals = y.getConfigurationSection("crystals");
      if (crystals != null)
        for (String s : crystals.getKeys(false))
          c.crystals.put(
              Integer.parseInt(s),
              new City.InstalledCrystal(
                  crystals.getString(s + ".id"),
                  UUID.fromString(crystals.getString(s + ".token"))));
      ConfigurationSection storage = y.getConfigurationSection("storage");
      if (storage != null)
        for (String s : storage.getKeys(false))
          c.storage.put(Integer.parseInt(s), storage.getItemStack(s));
      ConfigurationSection subs = y.getConfigurationSection("subzones");
      if (subs != null)
        for (String id : subs.getKeys(false)) {
          String p = "subzones." + id;
          City.Subzone s = new City.Subzone();
          s.id = UUID.fromString(id);
          s.name = y.getString(p + ".name");
          s.owner = UUID.fromString(y.getString(p + ".owner"));
          s.bounds = box(y, p + ".bounds", c.world);
          s.members.addAll(members(y, p + ".members"));
          s.flags.putAll(ConfigRules(y.getConfigurationSection(p + ".flags")));
          readIndividual(y, p + ".individual", s.individual);
          s.salePrice = y.getDouble(p + ".sale-price");
          s.saleCurrency = y.getString(p + ".sale-currency", "vault");
          s.forSale = y.getBoolean(p + ".for-sale");
          c.subzones.add(s);
        }
      return c;
    } catch (Exception e) {
      throw new IllegalArgumentException("Estado de ciudad corrupto", e);
    }
  }

  public static City copy(City c) {
    return decode(encode(c));
  }

  public static String item(ItemStack i) {
    YamlConfiguration y = new YamlConfiguration();
    y.set("item", i);
    return y.saveToString();
  }

  public static ItemStack item(String raw) {
    try {
      YamlConfiguration y = new YamlConfiguration();
      y.loadFromString(raw);
      return y.getItemStack("item");
    } catch (Exception e) {
      throw new IllegalArgumentException("Item corrupto", e);
    }
  }

  private static void box(ConfigurationSection y, String p, Cuboid b) {
    y.set(p + ".min", List.of(b.minX(), b.minY(), b.minZ()));
    y.set(p + ".max", List.of(b.maxX(), b.maxY(), b.maxZ()));
  }

  private static Cuboid box(ConfigurationSection y, String p, UUID w) {
    List<Integer> a = y.getIntegerList(p + ".min"), b = y.getIntegerList(p + ".max");
    if (a.size() != 3 || b.size() != 3) throw new IllegalArgumentException("Cuboid corrupto");
    return new Cuboid(w, a.get(0), a.get(1), a.get(2), b.get(0), b.get(1), b.get(2));
  }

  private static void members(ConfigurationSection y, String p, Set<UUID> s) {
    y.set(p, s.stream().map(UUID::toString).toList());
  }

  private static Set<UUID> members(ConfigurationSection y, String p) {
    Set<UUID> s = new LinkedHashSet<>();
    y.getStringList(p).forEach(v -> s.add(UUID.fromString(v)));
    return s;
  }

  private static void flags(ConfigurationSection y, String p, Map<String, FlagRule> r) {
    r.forEach(
        (k, v) -> {
          y.set(p + "." + k + ".state", v.allow() ? "ALLOW" : "DENY");
          y.set(p + "." + k + ".audience", v.audience().name());
        });
  }

  private static Map<String, FlagRule> ConfigRules(ConfigurationSection y) {
    return dev.marco035xd.terranova.config.ConfigCatalog.rules(y);
  }

  private static void individual(
      ConfigurationSection y, String p, Map<UUID, Map<String, Boolean>> r) {
    r.forEach((u, f) -> f.forEach((k, v) -> y.set(p + "." + u + "." + k, v)));
  }

  private static void readIndividual(
      ConfigurationSection y, String p, Map<UUID, Map<String, Boolean>> out) {
    ConfigurationSection a = y.getConfigurationSection(p);
    if (a != null)
      for (String u : a.getKeys(false)) {
        Map<String, Boolean> f = new LinkedHashMap<>();
        ConfigurationSection s = a.getConfigurationSection(u);
        for (String k : s.getKeys(false)) f.put(k, s.getBoolean(k));
        out.put(UUID.fromString(u), f);
      }
  }
}
