package dev.marco035xd.terranova.model;

import java.util.*;
import org.bukkit.inventory.ItemStack;

/** Main-thread-owned aggregate. Mutations are applied to a detached copy before commit. */
public final class City {
  public final UUID id;
  public UUID owner;
  public String type, name, tag = "";
  public int level, x, y, z;
  public UUID world;
  public Cuboid bounds;
  public long revision, lastActive = System.currentTimeMillis();
  public boolean hologram = true;
  public final Set<UUID> members = new LinkedHashSet<>();
  public final Map<String, FlagRule> flags = new LinkedHashMap<>();
  public final Map<UUID, Map<String, Boolean>> individual = new LinkedHashMap<>();
  public final List<Subzone> subzones = new ArrayList<>();
  public final Map<Integer, InstalledCrystal> crystals = new TreeMap<>();
  public final Map<Integer, ItemStack> storage = new TreeMap<>();
  public final Set<String> stealReceipts = new LinkedHashSet<>();
  public String shield = "";
  public long shieldUntil;

  public City(UUID id) {
    this.id = id;
  }

  public boolean isOwner(UUID u) {
    return owner.equals(u);
  }

  public boolean isMember(UUID u) {
    return isOwner(u) || members.contains(u);
  }

  public boolean shieldActive(long now) {
    return !shield.isEmpty() && shieldUntil > now;
  }

  public Subzone subzone(int x, int y, int z) {
    return subzones.stream()
        .filter(s -> s.bounds.contains(world, x, y, z))
        .findFirst()
        .orElse(null);
  }

  public record InstalledCrystal(String id, UUID token) {}

  public static final class Subzone {
    public UUID id = UUID.randomUUID(), owner;
    public String name;
    public Cuboid bounds;
    public final Set<UUID> members = new LinkedHashSet<>();
    public final Map<String, FlagRule> flags = new LinkedHashMap<>();
    public final Map<UUID, Map<String, Boolean>> individual = new LinkedHashMap<>();
    public double salePrice;
    public String saleCurrency = "vault";
    public boolean forSale;
  }
}
