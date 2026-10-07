package dev.marco035xd.terranova.service;

import dev.marco035xd.terranova.TerranovaPlugin;
import dev.marco035xd.terranova.config.ConfigCatalog;
import dev.marco035xd.terranova.model.*;
import dev.marco035xd.terranova.util.Text;
import java.util.*;
import java.util.function.*;
import org.bukkit.*;
import org.bukkit.boss.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

/**
 * Runtime overlays never mutate saved city rules. Type factories can be extended through the API.
 */
public final class EventService {
  public interface EventFactory {
    void started(Active event);

    void ended(Active event);
  }

  public static final class Active {
    public ConfigCatalog.EventDef definition;
    public final long started, expires;
    public BossBar bar;

    Active(ConfigCatalog.EventDef d, long start, long end) {
      definition = d;
      started = start;
      expires = end;
    }

    public String id() {
      return definition.id();
    }
  }

  private final TerranovaPlugin p;
  private final Map<String, Active> active = new java.util.concurrent.ConcurrentHashMap<>();
  private final Set<String> pending = new HashSet<>();
  private final Map<String, EventFactory> factories = new LinkedHashMap<>();
  private final Map<String, long[]> deferred = new LinkedHashMap<>();

  public EventService(TerranovaPlugin p) {
    this.p = p;
    EventFactory noTasks =
        new EventFactory() {
          public void started(Active e) {}

          public void ended(Active e) {}
        };
    register("RAID", noTasks);
    register("BOOSTER", noTasks);
  }

  public void register(String type, EventFactory f) {
    if (factories.putIfAbsent(type, f) != null)
      throw new IllegalArgumentException("Tipo de evento ya registrado");
    for (String id : new ArrayList<>(deferred.keySet())) {
      var definition = p.catalog().events.get(id);
      if (definition != null && definition.type().equals(type)) {
        long[] time = deferred.remove(id);
        if (time[1] > System.currentTimeMillis())
          add(new Active(definition, time[0], time[1]), false);
        else p.repository().event(id, 0, 0);
      }
    }
  }

  public void load() {
    long now = System.currentTimeMillis();
    p.repository()
        .events()
        .join()
        .forEach(
            (id, time) -> {
              var d = p.catalog().events.get(id);
              if (d != null && time[1] > now) {
                if (factories.containsKey(d.type())) add(new Active(d, time[0], time[1]), false);
                else deferred.put(id, time);
              } else p.repository().event(id, 0, 0);
            });
  }

  public Collection<Active> all() {
    return Collections.unmodifiableCollection(active.values());
  }

  public Active get(String id) {
    return active.get(id);
  }

  public void start(String id, long duration) {
    var d = p.catalog().events.get(id);
    if (d == null || !factories.containsKey(d.type()))
      throw new IllegalArgumentException("Evento inexistente o tipo no registrado");
    if (active.containsKey(id) || !pending.add(id))
      throw new IllegalArgumentException("Evento ya iniciado");
    long start = System.currentTimeMillis(), end = Math.addExact(start, duration);
    p.repository()
        .event(id, start, end)
        .whenComplete(
            (v, e) ->
                p.main(
                    () -> {
                      pending.remove(id);
                      if (e != null) p.failure(e);
                      else add(new Active(d, start, end), true);
                    }));
  }

  public static BarStyle style(String name) {
    String s = name.toUpperCase(Locale.ROOT);
    if (s.equals("PROGRESS")) return BarStyle.SOLID;
    if (s.startsWith("NOTCHED_")) s = s.replace("NOTCHED_", "SEGMENTED_");
    return BarStyle.valueOf(s);
  }

  private void add(Active e, boolean announce) {
    ConfigurationSection b = e.definition.yaml().getConfigurationSection("bossbar");
    if (b != null && b.getBoolean("enabled", true)) {
      e.bar =
          Bukkit.createBossBar(
              "",
              BarColor.valueOf(b.getString("color", "RED")),
              style(b.getString("overlay", "PROGRESS")));
    }
    active.put(e.id(), e);
    factories.get(e.definition.type()).started(e);
    if (announce) announce(e, "started");
    if (p.effects() != null) p.effects().refreshAll();
  }

  public void stop(String id) {
    Active e = active.get(id);
    if (e == null) throw new IllegalArgumentException("Evento inactivo");
    if (!pending.add(id)) return;
    p.repository()
        .event(id, 0, 0)
        .whenComplete(
            (v, error) ->
                p.main(
                    () -> {
                      pending.remove(id);
                      if (error != null) {
                        p.failure(error);
                        return;
                      }
                      active.remove(id);
                      if (e.bar != null) e.bar.removeAll();
                      factories.get(e.definition.type()).ended(e);
                      p.hits().clearEvent(id);
                      announce(e, "ended");
                      p.effects().refreshAll();
                      p.gui().refreshAll();
                    }));
  }

  public void tick() {
    long now = System.currentTimeMillis();
    for (Active e : new ArrayList<>(active.values())) {
      if (e.expires <= now) {
        stop(e.id());
        continue;
      }
      if (e.bar != null) {
        Map<String, Object> v =
            Map.of(
                "remaining",
                Text.remaining(e.expires - now),
                "event",
                e.id(),
                "duration",
                Text.remaining(e.expires - e.started));
        e.bar.setTitle(
            Text.color(
                Text.replace(
                    e.definition.yaml().getString("bossbar.title", "&c{event} &f{remaining}"), v)));
        e.bar.setProgress(
            Math.max(0, Math.min(1, (double) (e.expires - now) / (e.expires - e.started))));
        for (Player player : Bukkit.getOnlinePlayers()) {
          if (p.catalog().worldEnabled(player.getWorld())) e.bar.addPlayer(player);
          else e.bar.removePlayer(player);
        }
      }
    }
  }

  private void announce(Active e, String phase) {
    var y = e.definition.yaml();
    String prefix = y.getString("messages.prefix", "");
    Map<String, Object> v =
        Map.of(
            "prefix",
            prefix,
            "event",
            e.id(),
            "duration",
            Text.remaining(e.expires - e.started),
            "remaining",
            Text.remaining(e.expires - System.currentTimeMillis()));
    for (Player player : Bukkit.getOnlinePlayers()) {
      for (String s : y.getStringList("messages." + phase)) Text.send(player, Text.replace(s, v));
      ConfigurationSection f = y.getConfigurationSection("feedback." + phase);
      if (f != null) p.visual().feedback(player, f, v);
    }
  }

  public List<Active> ordered() {
    return active.values().stream()
        .filter(e -> e.expires > System.currentTimeMillis())
        .sorted(
            Comparator.<Active>comparingInt(e -> e.definition.priority())
                .reversed()
                .thenComparing(Active::id))
        .toList();
  }

  public FlagRule override(City city, String flag, UUID actor) {
    for (Active a : ordered()) {
      if (a.definition.type().equals("RAID") && city.shieldActive(System.currentTimeMillis()))
        continue;
      FlagRule rule =
          ConfigCatalog.rules(a.definition.yaml().getConfigurationSection("forced-flags"))
              .get(flag);
      if (rule != null && rule.audience().matches(actor, city.owner, city.members)) return rule;
    }
    return null;
  }

  public Active raid(City city) {
    if (city == null || city.shieldActive(System.currentTimeMillis())) return null;
    return ordered().stream()
        .filter(e -> e.definition.type().equals("RAID"))
        .findFirst()
        .orElse(null);
  }

  public boolean raidExists() {
    return ordered().stream().anyMatch(e -> e.definition.type().equals("RAID"));
  }

  public boolean canSteal(Player player, City city) {
    Active r = raid(city);
    return r != null
        && !city.isMember(player.getUniqueId())
        && city.bounds.contains(
            player.getWorld().getUID(),
            player.getLocation().getBlockX(),
            player.getLocation().getBlockY(),
            player.getLocation().getBlockZ())
        && player.hasPermission("terranova.raid.steal")
        && r.definition.yaml().getBoolean("crystals.stealable", false);
  }

  public boolean activation(City city, ConfigCatalog.Activation mode) {
    boolean raid = raid(city) != null;
    return switch (mode) {
      case ALWAYS -> true;
      case RAID_ONLY -> raid;
      case NON_RAID -> !raid;
    };
  }

  public void reload() {
    for (Active e : active.values()) {
      var d = p.catalog().events.get(e.id());
      if (d != null) e.definition = d;
    }
  }

  public void close() {
    for (Active e : active.values()) if (e.bar != null) e.bar.removeAll();
  }
}
