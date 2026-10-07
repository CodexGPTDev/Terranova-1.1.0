package dev.marco035xd.terranova.service;

import dev.marco035xd.terranova.TerranovaPlugin;
import dev.marco035xd.terranova.model.*;
import dev.marco035xd.terranova.util.Text;
import java.util.*;
import org.bukkit.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.*;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;

public final class VisualService {
  private record Border(UUID city, long expires) {}

  private final TerranovaPlugin p;
  private final Map<UUID, List<TextDisplay>> holograms = new HashMap<>();
  private final Map<UUID, Border> borders = new HashMap<>();
  private final NamespacedKey marker;

  public VisualService(TerranovaPlugin p) {
    this.p = p;
    marker = new NamespacedKey(p, "hologram-city");
  }

  public void cleanupChunk(org.bukkit.Chunk chunk) {
    for (Entity e : chunk.getEntities())
      if (e.getPersistentDataContainer().has(marker, PersistentDataType.STRING)) e.remove();
    for (City c : p.cities().all())
      if (c.world.equals(chunk.getWorld().getUID())
          && c.x >> 4 == chunk.getX()
          && c.z >> 4 == chunk.getZ()) {
        remove(c.id);
        refresh(c);
      }
  }

  public void refresh(City city) {
    remove(city.id);
    World w = Bukkit.getWorld(city.world);
    if (w == null
        || !w.isChunkLoaded(city.x >> 4, city.z >> 4)
        || !city.hologram
        || !p.catalog().settings.getBoolean("holograms.enabled", true)) return;
    List<String> lines = p.catalog().settings.getStringList("holograms.lines");
    double spacing = p.catalog().settings.getDouble("holograms.line-spacing", .25),
        height = p.catalog().settings.getDouble("holograms.height", 2);
    List<TextDisplay> entities = new ArrayList<>();
    Map<String, Object> v = p.cities().placeholders(city);
    for (int n = 0; n < lines.size(); n++) {
      String text = Text.color(Text.replace(lines.get(n), v));
      if (text.isBlank()) continue;
      Location at =
          new Location(w, city.x + .5, city.y + height + (lines.size() - n) * spacing, city.z + .5);
      TextDisplay d =
          w.spawn(
              at,
              TextDisplay.class,
              e -> {
                e.setText(text);
                e.setBillboard(Display.Billboard.CENTER);
                e.setAlignment(
                    TextDisplay.TextAlignment.valueOf(
                        p.catalog().settings.getString("holograms.alignment", "CENTER")));
                e.setSeeThrough(false);
                e.setShadowed(true);
                e.setDefaultBackground(false);
                e.setBackgroundColor(Color.fromARGB(0));
                e.setPersistent(false);
                e.setViewRange(
                    (float) (p.catalog().settings.getDouble("holograms.view-distance", 32) / 64));
                e.getPersistentDataContainer()
                    .set(marker, PersistentDataType.STRING, city.id.toString());
              });
      entities.add(d);
    }
    holograms.put(city.id, entities);
  }

  public void remove(UUID city) {
    var entities = holograms.remove(city);
    if (entities != null) entities.forEach(Entity::remove);
  }

  public void border(Player player, City city) {
    borders.put(
        player.getUniqueId(),
        new Border(
            city.id,
            System.currentTimeMillis()
                + Text.duration(p.catalog().settings.getString("border.duration", "15s"))));
  }

  public void tick() {
    long now = System.currentTimeMillis();
    borders
        .entrySet()
        .removeIf(
            e ->
                e.getValue().expires < now
                    || Bukkit.getPlayer(e.getKey()) == null
                    || p.cities().get(e.getValue().city) == null);
    for (var e : borders.entrySet()) {
      Player player = Bukkit.getPlayer(e.getKey());
      City city = p.cities().get(e.getValue().city);
      draw(player, city.bounds, player.getLocation().getY(), false);
    }
    if (!p.catalog().settings.getBoolean("core-effects.particles.enabled", true)) return;
    double radius = p.catalog().settings.getDouble("core-effects.activation-distance", 32);
    int count =
        Math.min(100, Math.max(0, p.catalog().settings.getInt("core-effects.particles.count", 3)));
    Particle particle =
        Particle.valueOf(p.catalog().settings.getString("core-effects.particles.type", "END_ROD"));
    for (Player player : Bukkit.getOnlinePlayers()) {
      for (City c : p.cities().nearby(player.getLocation(), (int) Math.ceil(radius))) {
        if (!player.getWorld().getUID().equals(c.world)) continue;
        double dx = player.getLocation().getX() - c.x, dz = player.getLocation().getZ() - c.z;
        if (dx * dx + dz * dz > radius * radius) continue;
        double r = p.catalog().settings.getDouble("core-effects.particles.radius", 1.2);
        player.spawnParticle(particle, c.x + .5, c.y + 1.4, c.z + .5, count, r, .5, r, 0);
      }
    }
  }

  public void draw(Player player, Cuboid b, double y, boolean cuboid) {
    if (player == null || !player.getWorld().getUID().equals(b.world())) return;
    int budget =
        Math.min(
            2000,
            Math.max(12, p.catalog().settings.getInt("border.max-particles-per-player", 180)));
    double distance = p.catalog().settings.getDouble("border.render-distance", 64);
    Particle particle =
        Particle.valueOf(
            p.catalog()
                .settings
                .getString(cuboid ? "subzones.selection-particle" : "border.particle", "END_ROD"));
    double bottom = cuboid ? b.minY() : Math.max(b.minY(), Math.min(b.maxY(), y - 1)),
        top =
            cuboid
                ? b.maxY() + 1
                : bottom + p.catalog().settings.getDouble("border.corner-height", 8);
    double[][] corners = {
      {b.minX(), bottom, b.minZ()},
      {b.maxX() + 1, bottom, b.minZ()},
      {b.maxX() + 1, bottom, b.maxZ() + 1},
      {b.minX(), bottom, b.maxZ() + 1},
      {b.minX(), top, b.minZ()},
      {b.maxX() + 1, top, b.minZ()},
      {b.maxX() + 1, top, b.maxZ() + 1},
      {b.minX(), top, b.maxZ() + 1}
    };
    int[][] edges = {
      {0, 1}, {1, 2}, {2, 3}, {3, 0}, {4, 5}, {5, 6}, {6, 7}, {7, 4}, {0, 4}, {1, 5}, {2, 6}, {3, 7}
    };
    // Sample the visible portion of each edge; cost is bounded even for enormous cuboids.
    int perEdge = budget / edges.length;
    Vector eye = player.getLocation().toVector();
    for (int[] edge : edges) {
      Vector start = new Vector(corners[edge[0]][0], corners[edge[0]][1], corners[edge[0]][2]),
          end = new Vector(corners[edge[1]][0], corners[edge[1]][1], corners[edge[1]][2]);
      Vector delta = end.clone().subtract(start);
      double len = delta.length();
      if (len == 0) continue;
      Vector unit = delta.clone().multiply(1 / len);
      double projection = eye.clone().subtract(start).dot(unit),
          lo = Math.max(0, projection - distance),
          hi = Math.min(len, projection + distance);
      if (hi < lo) continue;
      for (int n = 0; n < perEdge; n++) {
        double t = lo + (hi - lo) * n / Math.max(1, perEdge - 1);
        Vector pos = start.clone().add(unit.clone().multiply(t));
        if (pos.distanceSquared(eye) > distance * distance) continue;
        double py = pos.getY();
        if (!cuboid
            && edge[0] < 4
            && edge[1] < 4
            && p.catalog().settings.getBoolean("border.adapt-to-terrain", true)) {
          int bx = (int) Math.floor(pos.getX()), bz = (int) Math.floor(pos.getZ());
          if (player.getWorld().isChunkLoaded(bx >> 4, bz >> 4))
            py = player.getWorld().getHighestBlockYAt(bx, bz) + 1.05;
        }
        player.spawnParticle(particle, pos.getX(), py, pos.getZ(), 1, 0, 0, 0, 0);
      }
    }
  }

  public void feedback(Player player, String id, Map<String, ?> vars) {
    feedback(player, p.catalog().settings.getConfigurationSection("feedback." + id), vars);
  }

  public void feedback(Player player, ConfigurationSection s, Map<String, ?> vars) {
    if (player == null || s == null) return;
    String title = Text.color(Text.replace(s.getString("title", ""), vars)),
        subtitle = Text.color(Text.replace(s.getString("subtitle", ""), vars));
    if (!title.isEmpty() || !subtitle.isEmpty()) player.sendTitle(title, subtitle, 10, 50, 15);
    if (s.contains("actionbar")) Text.action(player, Text.replace(s.getString("actionbar"), vars));
    Text.sound(player, s.getString("sound", ""));
    if (s.contains("particle")) {
      Particle particle = Particle.valueOf(s.getString("particle"));
      player.spawnParticle(
          particle,
          player.getLocation().add(0, 1, 0),
          Math.min(150, s.getInt("particle-count", 30)),
          .7,
          .7,
          .7,
          0);
    }
  }

  public void forget(UUID u) {
    borders.remove(u);
  }

  public void close() {
    for (UUID id : new ArrayList<>(holograms.keySet())) remove(id);
    borders.clear();
  }
}
