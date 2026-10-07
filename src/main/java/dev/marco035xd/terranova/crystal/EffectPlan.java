package dev.marco035xd.terranova.crystal;

import java.util.*;
import org.bukkit.Material;
import org.bukkit.potion.*;

public final class EffectPlan {
  public record Multiplier(double value, String mode) {}

  public final Map<PotionEffectType, PotionEffect> potions = new LinkedHashMap<>();
  public final Set<Material> blocked = new HashSet<>();
  public final Set<String> advancedDenied = new HashSet<>();
  public final Map<String, List<Multiplier>> xp = new LinkedHashMap<>();
  public final List<Multiplier> mana = new ArrayList<>();
  public boolean flight;
  public int raidHits;
  public double cropMultiplier = 1;

  public static double aggregate(List<Multiplier> list) {
    double value = 1;
    for (Multiplier m : list)
      value =
          switch (m.mode) {
            case "HIGHEST" -> Math.max(value, m.value);
            case "ADD" -> value + (m.value - 1);
            default -> value * m.value;
          };
    return Math.max(0, Math.min(1000, Double.isFinite(value) ? value : 1000));
  }
}
