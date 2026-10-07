package dev.marco035xd.terranova.service;

import dev.marco035xd.terranova.TerranovaPlugin;
import dev.marco035xd.terranova.model.City;
import dev.marco035xd.terranova.util.Text;
import java.util.*;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

public final class RaidHitService {
  public record Position(UUID world, int x, int y, int z) {
    public static Position of(Block b) {
      return new Position(b.getWorld().getUID(), b.getX(), b.getY(), b.getZ());
    }
  }

  private record ActorBlock(UUID player, Position block) {}

  private record Progress(String event, int count, long touched) {}

  private final TerranovaPlugin p;
  private final Map<ActorBlock, Long> cooldown = new HashMap<>();
  private final Map<Position, Progress> hits = new HashMap<>();

  public RaidHitService(TerranovaPlugin p) {
    this.p = p;
  }

  public boolean enabled(City city, Player actor, Block b) {
    var raid = p.events().raid(city);
    if (raid == null
        || city.isMember(actor.getUniqueId())
        || !raid.definition.yaml().getBoolean("breaking.enabled", true)
        || p.cities().core(city, b)) return false;
    return true;
  }

  public boolean hit(Player player, City city, Block block) {
    var raid = p.events().raid(city);
    if (raid == null) return false;
    var y = raid.definition.yaml();
    List<String> blocks = y.getStringList("breaking.blocks");
    boolean listed = blocks.contains(block.getType().name());
    String mode = y.getString("breaking.mode", "BLACKLIST");
    if (mode.equals("WHITELIST") ? !listed : listed) return false;
    long now = System.currentTimeMillis(),
        delay = Math.max(1, y.getLong("breaking.hit-cooldown-millis", 250));
    Position pos = Position.of(block);
    ActorBlock key = new ActorBlock(player.getUniqueId(), pos);
    Long previous = cooldown.get(key);
    if (previous != null && now < previous) return false;
    if (cooldown.size() >= p.catalog().settings.getInt("performance.max-hit-entries", 50000))
      clean(now);
    if (cooldown.size() >= p.catalog().settings.getInt("performance.max-hit-entries", 50000))
      return false;
    cooldown.put(key, Math.addExact(now, delay));
    long expire = Text.duration(y.getString("breaking.block-hit-expiration", "30s"));
    Progress old = hits.get(pos);
    int count =
        old == null || now - old.touched > expire || !old.event.equals(raid.id())
            ? 1
            : old.count + 1;
    int target = Math.max(1, p.cities().level(city).raidHits() + p.effects().raidBonus(city));
    if (hits.size() >= p.catalog().settings.getInt("performance.max-hit-entries", 50000)
        && old == null) return false;
    hits.put(pos, new Progress(raid.id(), count, now));
    if (y.getBoolean("breaking.feedback.actionbar", true)) {
      int visible = Math.min(10, target),
          filled = (int) Math.ceil((double) Math.min(count, target) / target * visible);
      Text.action(
          player,
          "&c"
              + "▰".repeat(filled)
              + "&7"
              + "▱".repeat(visible - filled)
              + " &7("
              + count
              + "/"
              + target
              + ")");
    }
    Text.sound(player, y.getString("breaking.feedback.sound", "BLOCK_STONE_HIT"));
    if (count >= target) {
      hits.remove(pos);
      return true;
    }
    return false;
  }

  public boolean dropItems(City city) {
    var r = p.events().raid(city);
    return r != null && r.definition.yaml().getBoolean("breaking.drop-items", true);
  }

  public void clean(long now) {
    cooldown.entrySet().removeIf(e -> now >= e.getValue());
    hits.entrySet()
        .removeIf(
            e -> {
              var r = p.events().get(e.getValue().event);
              return r == null
                  || now - e.getValue().touched
                      > Text.duration(
                          r.definition.yaml().getString("breaking.block-hit-expiration", "30s"));
            });
  }

  public void clearEvent(String id) {
    hits.entrySet().removeIf(e -> e.getValue().event.equals(id));
    cooldown.clear();
  }

  public void forget(UUID player) {
    cooldown.keySet().removeIf(k -> k.player.equals(player));
  }
}
