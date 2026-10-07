package dev.marco035xd.terranova.crystal;

import dev.marco035xd.terranova.TerranovaPlugin;
import dev.marco035xd.terranova.model.City;
import java.util.*;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.potion.*;

/** Region tracking + bounded potion leases; only relevant players are recomputed. */
public final class CrystalEffectEngine {
  private record Applied(PotionEffect ours, PotionEffect previous, long time) {}

  private final TerranovaPlugin p;
  private final CrystalEffectRegistry registry = new CrystalEffectRegistry();
  private final Map<UUID, UUID> locations = new HashMap<>();
  private final Map<UUID, EffectPlan> plans = new HashMap<>();
  private final Map<UUID, Map<PotionEffectType, Applied>> owned = new HashMap<>();
  private final Map<UUID, Boolean> flightBefore = new HashMap<>();
  private final Set<UUID> applying = new HashSet<>();

  public CrystalEffectEngine(TerranovaPlugin p) {
    this.p = p;
    Modules.register(registry);
  }

  public CrystalEffectRegistry registry() {
    return registry;
  }

  public EffectPlan plan(Player player, City city) {
    EffectPlan out = new EffectPlan();
    if (city == null) return out;
    for (var installed : city.crystals.values()) {
      var d = p.catalog().crystals.get(installed.id());
      if (d == null
          || city.level < d.requiredLevel()
          || !p.events().activation(city, d.activation())) continue;
      for (var handler : registry.handlers()) {
        var module = d.yaml().getConfigurationSection(handler.path());
        if (module != null) handler.contribute(player, city, module, out);
      }
    }
    for (var event : p.events().ordered())
      if (event.definition.type().equals("BOOSTER")) {
        var aura = event.definition.yaml().getConfigurationSection("aura-skills");
        if (aura != null)
          new Modules.AuraSkillsCrystalEffectHandler().contribute(player, city, aura, out);
        var growth = event.definition.yaml().getConfigurationSection("effects.crop-growth");
        if (growth != null)
          new Modules.CropGrowthCrystalEffectHandler().contribute(player, city, growth, out);
      }
    return out;
  }

  public void refresh(Player player) {
    if (player == null || !player.isOnline()) return;
    City c = p.cities().at(player.getLocation());
    UUID u = player.getUniqueId();
    if (c == null) locations.remove(u);
    else locations.put(u, c.id);
    EffectPlan next = plan(player, c);
    plans.put(u, next);
    apply(player, next);
  }

  public void refreshCity(UUID id) {
    for (Player player : Bukkit.getOnlinePlayers()) {
      City c = p.cities().at(player.getLocation());
      if (id.equals(locations.get(player.getUniqueId())) || (c != null && c.id.equals(id)))
        refresh(player);
    }
  }

  public void refreshAll() {
    for (Player player : Bukkit.getOnlinePlayers()) refresh(player);
  }

  public void renew() {
    for (UUID u : new ArrayList<>(plans.keySet())) {
      Player player = Bukkit.getPlayer(u);
      if (player != null) apply(player, plans.get(u));
    }
  }

  private void apply(Player player, EffectPlan plan) {
    UUID u = player.getUniqueId();
    Map<PotionEffectType, Applied> current = owned.computeIfAbsent(u, k -> new HashMap<>());
    applying.add(u);
    try {
      for (PotionEffectType type : new ArrayList<>(current.keySet()))
        if (!plan.potions.containsKey(type)) release(player, type, current.remove(type));
      for (PotionEffect effect : plan.potions.values()) {
        PotionEffect external = player.getPotionEffect(effect.getType());
        Applied previous = current.get(effect.getType());
        if (external != null
            && (previous == null || external.getAmplifier() != previous.ours.getAmplifier())
            && (external.getAmplifier() > effect.getAmplifier()
                || external.getAmplifier() == effect.getAmplifier()
                    && (external.getDuration() == PotionEffect.INFINITE_DURATION
                        || external.getDuration() > effect.getDuration()))) continue;
        PotionEffect baseline = previous == null ? external : previous.previous;
        long origin = previous == null ? System.currentTimeMillis() : previous.time;
        if (player.addPotionEffect(effect)) {
          current.put(effect.getType(), new Applied(effect, baseline, origin));
        }
      }
      if (plan.flight
          && (player.getGameMode() == GameMode.SURVIVAL
              || player.getGameMode() == GameMode.ADVENTURE)) {
        flightBefore.putIfAbsent(u, player.getAllowFlight());
        player.setAllowFlight(true);
      } else if (flightBefore.containsKey(u)) {
        boolean before = flightBefore.remove(u);
        if (player.getGameMode() != GameMode.CREATIVE
            && player.getGameMode() != GameMode.SPECTATOR) {
          if (!before) player.setFlying(false);
          player.setAllowFlight(before);
        }
      }
    } finally {
      applying.remove(u);
    }
  }

  private void release(Player player, PotionEffectType type, Applied a) {
    if (a == null) return;
    PotionEffect now = player.getPotionEffect(type);
    if (now != null
        && now.getAmplifier() == a.ours.getAmplifier()
        && now.isAmbient() == a.ours.isAmbient()
        && now.hasParticles() == a.ours.hasParticles()
        && now.hasIcon() == a.ours.hasIcon()) {
      player.removePotionEffect(type);
      if (a.previous != null) {
        int elapsed = (int) Math.min(Integer.MAX_VALUE, (System.currentTimeMillis() - a.time) / 50);
        int left =
            a.previous.getDuration() == PotionEffect.INFINITE_DURATION
                ? PotionEffect.INFINITE_DURATION
                : a.previous.getDuration() - elapsed;
        if (left > 0 || left == PotionEffect.INFINITE_DURATION)
          player.addPotionEffect(
              new PotionEffect(
                  type,
                  left,
                  a.previous.getAmplifier(),
                  a.previous.isAmbient(),
                  a.previous.hasParticles(),
                  a.previous.hasIcon()));
      }
    }
  }

  public void externalPotionChange(Player player, PotionEffectType type) {
    if (!applying.contains(player.getUniqueId())) {
      Map<PotionEffectType, Applied> m = owned.get(player.getUniqueId());
      if (m != null) m.remove(type);
    }
  }

  public boolean blocked(Player player, City city, Material material) {
    return plan(player, city).blocked.contains(material);
  }

  public boolean denies(Player player, City city, String flag) {
    return (flag.equals("enderpearl") && blocked(player, city, Material.ENDER_PEARL))
        || (flag.equals("chorus-fruit-teleport") && blocked(player, city, Material.CHORUS_FRUIT));
  }

  public boolean advancedDenied(Player player, String enchant) {
    City c = p.cities().at(player.getLocation());
    return c != null && plan(player, c).advancedDenied.contains(enchant.toUpperCase(Locale.ROOT));
  }

  public double xp(Player player, String skill) {
    City c = p.cities().at(player.getLocation());
    return EffectPlan.aggregate(
        plan(player, c).xp.getOrDefault(skill.toUpperCase(Locale.ROOT), List.of()));
  }

  public double mana(Player player) {
    return EffectPlan.aggregate(plan(player, p.cities().at(player.getLocation())).mana);
  }

  public int raidBonus(City city) {
    return plan(Bukkit.getPlayer(city.owner), city).raidHits;
  }

  public double crop(City city) {
    return plan(Bukkit.getPlayer(city.owner), city).cropMultiplier;
  }

  public void forget(Player player) {
    apply(player, new EffectPlan());
    UUID u = player.getUniqueId();
    locations.remove(u);
    plans.remove(u);
    owned.remove(u);
    flightBefore.remove(u);
  }

  public void close() {
    for (Player player : Bukkit.getOnlinePlayers()) forget(player);
  }
}
