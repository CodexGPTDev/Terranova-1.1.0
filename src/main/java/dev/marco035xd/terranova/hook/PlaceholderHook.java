package dev.marco035xd.terranova.hook;

import dev.marco035xd.terranova.TerranovaPlugin;
import dev.marco035xd.terranova.model.City;
import dev.marco035xd.terranova.util.Text;
import java.util.*;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;

public final class PlaceholderHook extends PlaceholderExpansion {
  private final TerranovaPlugin p;

  public PlaceholderHook(TerranovaPlugin p) {
    this.p = p;
  }

  public String getIdentifier() {
    return "terranova";
  }

  public String getAuthor() {
    return "Marco035xd";
  }

  public String getVersion() {
    return p.getDescription().getVersion();
  }

  public boolean persist() {
    return true;
  }

  public String onRequest(OfflinePlayer player, String key) {
    if (key.startsWith("event_")) {
      String rest = key.substring(6),
          suffix = rest.endsWith("_remaining_seconds") ? "_remaining_seconds" : "_active";
      String id = rest.substring(0, Math.max(0, rest.length() - suffix.length()));
      var e = p.events().get(id);
      return suffix.equals("_active")
          ? String.valueOf(e != null && e.expires > System.currentTimeMillis())
          : String.valueOf(
              e == null ? 0 : Math.max(0, (e.expires - System.currentTimeMillis()) / 1000));
    }
    if (player == null) return "";
    City c = player.isOnline() ? p.cities().at(player.getPlayer().getLocation()) : null;
    if (c == null) c = p.cities().primary(player.getUniqueId());
    if (key.equals("has_city")) return String.valueOf(c != null);
    if (key.equals("owned_protections"))
      return String.valueOf(
          p.cities().all().stream().filter(v -> v.isOwner(player.getUniqueId())).count());
    if (c == null) return "";
    if (key.equals("city_id")) return c.id.toString();
    if (key.equals("city_type")) return c.type;
    if (key.equals("city_role"))
      return c.isOwner(player.getUniqueId())
          ? "owner"
          : c.isMember(player.getUniqueId()) ? "member" : "visitor";
    if (key.equals("city_shield_active"))
      return String.valueOf(c.shieldActive(System.currentTimeMillis()));
    if (key.equals("city_raid_active")) return String.valueOf(p.events().raid(c) != null);
    if (key.equals("city_shield_remaining_seconds"))
      return String.valueOf(Math.max(0, (c.shieldUntil - System.currentTimeMillis()) / 1000));
    if (key.startsWith("city_flag_")) {
      var rule = c.flags.get(key.substring(10).replace('_', '-'));
      return rule == null ? "" : rule.allow() ? "allow" : "deny";
    }
    String normalized =
        key.startsWith("city_") ? key.substring(5).replace('_', '-') : key.replace('_', '-');
    if (normalized.equals("name")) normalized = "city";
    if (normalized.equals("name-plain")) return org.bukkit.ChatColor.stripColor(Text.color(c.name));
    return String.valueOf(p.cities().placeholders(c).getOrDefault(normalized, ""));
  }
}
