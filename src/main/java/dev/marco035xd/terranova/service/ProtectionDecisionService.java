package dev.marco035xd.terranova.service;

import dev.marco035xd.terranova.TerranovaPlugin;
import dev.marco035xd.terranova.model.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.*;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.bukkit.inventory.ItemStack;

public final class ProtectionDecisionService {
  public record Decision(boolean allowed, String reason, UUID city) {}

  public interface ExternalGuard {
    boolean allows(Player actor, Location at, String action);
  }

  private final TerranovaPlugin p;
  private final List<ExternalGuard> external = new CopyOnWriteArrayList<>();

  public ProtectionDecisionService(TerranovaPlugin p) {
    this.p = p;
  }

  public void register(ExternalGuard g) {
    external.add(g);
  }

  public Decision decide(Player actor, Location at, String flag) {
    if (actor != null && actor.hasPermission("terranova.bypass"))
      return result(true, "ADMIN_BYPASS", null);
    if (at == null || at.getWorld() == null || !p.catalog().worldEnabled(at.getWorld()))
      return result(true, "WORLD_DISABLED", null);
    City c = p.cities().at(at);
    if (c == null) return result(true, "WILDERNESS", null);
    UUID u = actor == null ? null : actor.getUniqueId();
    City.Subzone sub = c.subzone(at.getBlockX(), at.getBlockY(), at.getBlockZ());
    UUID owner = sub == null ? c.owner : sub.owner;
    Set<UUID> members = sub == null ? c.members : sub.members;
    boolean ownerAccess = u != null && u.equals(owner);
    if (sub != null
        && c.isOwner(u)
        && p.catalog().settings.getBoolean("subzones.city-owner-access", true)) ownerAccess = true;
    boolean baseline = ownerAccess || members.contains(u);
    Map<String, Boolean> personal = (sub == null ? c.individual : sub.individual).get(u);
    if (personal != null && personal.containsKey(flag)) baseline = personal.get(flag);
    FlagRule base = c.flags.get(flag);
    if (sub != null && sub.flags.containsKey(flag)) base = sub.flags.get(flag);
    if (!(personal != null && personal.containsKey(flag))
        && base != null
        && base.audience().matches(u, owner, members)) baseline = base.allow();
    if (actor == null && base == null) baseline = true;
    if (flag.equals("block-break") || flag.equals("block-place")) {
      FlagRule master =
          (sub != null && sub.flags.containsKey("build"))
              ? sub.flags.get("build")
              : c.flags.get("build");
      if (master != null
          && master.audience().matches(u, owner, members)
          && !master.allow()
          && !(personal != null && Boolean.TRUE.equals(personal.get(flag)))) baseline = false;
    }
    FlagRule event = p.events().override(c, flag, u);
    if (event != null) baseline = event.allow();
    // Crystal denials are final and cannot be weakened by a permissive raid flag.
    if (actor != null && p.effects().denies(actor, c, flag))
      return result(false, "CRYSTAL_DENIED", c.id);
    if (!baseline)
      return result(
          false,
          c.shieldActive(System.currentTimeMillis()) ? "SHIELDED_BASE_RULE" : "FLAG_DENIED",
          c.id);
    if (actor != null)
      for (ExternalGuard guard : external)
        if (!guard.allows(actor, at, flag)) return result(false, "EXTERNAL_GUARD", c.id);
    return result(true, event == null ? "BASE_RULE" : "EVENT_OVERRIDE", c.id);
  }

  private Decision result(boolean a, String reason, UUID city) {
    return new Decision(a, reason, city);
  }

  public boolean canBreak(Player player, Location at, Block block) {
    City c = p.cities().at(at);
    if (p.cities().core(c, block)) return false;
    if (c != null
        && !c.isMember(player.getUniqueId())
        && !player.hasPermission("terranova.bypass")) {
      var raid = p.events().raid(c);
      if (raid != null) {
        var y = raid.definition.yaml();
        if (!y.getBoolean("breaking.enabled", true)) return false;
        boolean listed = y.getStringList("breaking.blocks").contains(block.getType().name());
        if (y.getString("breaking.mode", "BLACKLIST").equals("WHITELIST") ? !listed : listed)
          return false;
      }
    }
    return decide(player, at, "block-break").allowed;
  }

  public boolean canPlace(Player player, Location at, Material material) {
    return decide(player, at, "block-place").allowed;
  }

  public boolean canInteract(Player player, Location at, Object target) {
    return decide(
            player, at, target instanceof org.bukkit.block.Container ? "chest-access" : "interact")
        .allowed;
  }

  public boolean canUseItem(Player player, Location at, ItemStack item) {
    City city = p.cities().at(at);
    if (city != null && item != null && p.effects().blocked(player, city, item.getType()))
      return false;
    return decide(player, at, "use").allowed;
  }

  public boolean canEnter(Player player, City city) {
    World w = Bukkit.getWorld(city.world);
    return w == null || decide(player, new Location(w, city.x, city.y, city.z), "entry").allowed;
  }

  public boolean canDamage(Player player, Entity entity) {
    String f =
        entity instanceof Player
            ? "pvp"
            : entity instanceof Villager
                ? "damage-villagers"
                : entity instanceof Animals ? "damage-animals" : "damage-mobs";
    return decide(player, entity.getLocation(), f).allowed;
  }

  public boolean canTeleport(Player player, Location from, Location to, TeleportCause cause) {
    String f =
        switch (cause) {
          case ENDER_PEARL -> "enderpearl";
          case CHORUS_FRUIT -> "chorus-fruit-teleport";
          case NETHER_PORTAL, END_PORTAL -> "use-portals";
          default -> "entry";
        };
    Material material =
        cause == TeleportCause.ENDER_PEARL
            ? Material.ENDER_PEARL
            : cause == TeleportCause.CHORUS_FRUIT ? Material.CHORUS_FRUIT : null;
    for (Location at : List.of(from, to)) {
      City c = p.cities().at(at);
      if (c != null && material != null && p.effects().blocked(player, c, material)) return false;
      if (!decide(player, at, f).allowed) return false;
    }
    return decide(player, to, "entry").allowed && decide(player, from, "exit").allowed;
  }
}
