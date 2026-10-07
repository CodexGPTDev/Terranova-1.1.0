package dev.marco035xd.terranova.crystal;

import dev.marco035xd.terranova.config.ConfigCatalog;
import dev.marco035xd.terranova.model.*;
import java.util.*;
import org.bukkit.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.potion.*;

public final class Modules {
  private Modules() {}

  private abstract static class Module implements CrystalEffectHandler {
    private final String path;

    Module(String path) {
      this.path = path;
    }

    public String path() {
      return path;
    }

    protected boolean audience(Player player, City city, ConfigurationSection s) {
      return Audience.parse(
              s.getString(
                  "audience",
                  s.getParent() == null
                      ? "MEMBERS"
                      : s.getParent().getString("audience", "MEMBERS")))
          .matches(player == null ? city.owner : player.getUniqueId(), city.owner, city.members);
    }
  }

  public static final class PotionCrystalEffectHandler extends Module {
    public PotionCrystalEffectHandler() {
      super("effects.potion-effects");
    }

    public void contribute(Player player, City city, ConfigurationSection s, EffectPlan plan) {
      if (!audience(player, city, s)) return;
      for (Map<?, ?> m : s.getMapList("list")) {
        PotionEffectType type = potion(String.valueOf(m.get("type")));
        if (type == null)
          throw new IllegalArgumentException("PotionEffectType inválido: " + m.get("type"));
        int amp = ((Number) m.get("level")).intValue() - 1;
        PotionEffect next =
            new PotionEffect(
                type,
                160,
                amp,
                bool(m, "ambient", true),
                bool(m, "particles", true),
                bool(m, "icon", true));
        PotionEffect old = plan.potions.get(type);
        if (old == null || old.getAmplifier() < amp) plan.potions.put(type, next);
      }
    }

    private static boolean bool(Map<?, ?> m, String key, boolean def) {
      Object v = m.get(key);
      return v instanceof Boolean b ? b : def;
    }

    public static PotionEffectType potion(String s) {
      String id = s.toUpperCase(Locale.ROOT);
      var byName = PotionEffectType.getByName(id);
      if (byName != null) return byName;
      return Registry.EFFECT.get(NamespacedKey.minecraft(id.toLowerCase(Locale.ROOT)));
    }
  }

  public static final class FlightCrystalEffectHandler extends Module {
    public FlightCrystalEffectHandler() {
      super("effects.flight");
    }

    public void contribute(Player player, City city, ConfigurationSection s, EffectPlan plan) {
      if (audience(player, city, s)) plan.flight |= s.getBoolean("enabled", true);
    }
  }

  public static final class BlockedItemCrystalEffectHandler extends Module {
    public BlockedItemCrystalEffectHandler() {
      super("blocked-items");
    }

    public void contribute(Player player, City city, ConfigurationSection s, EffectPlan plan) {
      if (audience(player, city, s))
        s.getStringList("materials").forEach(v -> plan.blocked.add(ConfigCatalog.material(v)));
    }
  }

  public static final class AdvancedEnchantmentsCrystalEffectHandler extends Module {
    public AdvancedEnchantmentsCrystalEffectHandler() {
      super("advanced-enchantments");
    }

    public void contribute(Player player, City city, ConfigurationSection s, EffectPlan plan) {
      if (audience(player, city, s))
        s.getStringList("denied").forEach(v -> plan.advancedDenied.add(v.toUpperCase(Locale.ROOT)));
    }
  }

  public static final class AuraSkillsCrystalEffectHandler extends Module {
    public AuraSkillsCrystalEffectHandler() {
      super("aura-skills");
    }

    public void contribute(Player player, City city, ConfigurationSection s, EffectPlan plan) {
      if (!audience(player, city, s)) return;
      String mode = s.getString("stacking-mode", "MULTIPLY");
      for (Map<?, ?> row : s.getMapList("xp-multipliers")) {
        double value = ((Number) row.get("multiplier")).doubleValue();
        Object keys = row.get("skills");
        if (keys instanceof List<?> list)
          for (Object skill : list)
            plan.xp
                .computeIfAbsent(
                    String.valueOf(skill).toUpperCase(Locale.ROOT), k -> new ArrayList<>())
                .add(new EffectPlan.Multiplier(value, mode));
      }
      plan.mana.add(
          new EffectPlan.Multiplier(s.getDouble("mana-regeneration.multiplier", 1), mode));
    }
  }

  public static final class RaidResistanceCrystalEffectHandler extends Module {
    public RaidResistanceCrystalEffectHandler() {
      super("effects.raid-resistance");
    }

    public void contribute(Player player, City city, ConfigurationSection s, EffectPlan plan) {
      if (audience(player, city, s))
        plan.raidHits =
            (int)
                Math.min(
                    1000000, (long) plan.raidHits + Math.max(0, s.getInt("additional-hits", 1)));
    }
  }

  public static final class CropGrowthCrystalEffectHandler extends Module {
    public CropGrowthCrystalEffectHandler() {
      super("effects.crop-growth");
    }

    public void contribute(Player player, City city, ConfigurationSection s, EffectPlan plan) {
      if (audience(player, city, s)) {
        double v = s.getDouble("multiplier", 1);
        plan.cropMultiplier = Math.min(1000, Math.max(0, plan.cropMultiplier * v));
      }
    }
  }

  public static void register(CrystalEffectRegistry r) {
    r.register(new PotionCrystalEffectHandler());
    r.register(new FlightCrystalEffectHandler());
    r.register(new BlockedItemCrystalEffectHandler());
    r.register(new AdvancedEnchantmentsCrystalEffectHandler());
    r.register(new AuraSkillsCrystalEffectHandler());
    r.register(new RaidResistanceCrystalEffectHandler());
    r.register(new CropGrowthCrystalEffectHandler());
  }
}
