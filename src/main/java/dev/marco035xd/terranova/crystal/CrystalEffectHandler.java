package dev.marco035xd.terranova.crystal;

import dev.marco035xd.terranova.model.City;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

/** Registered modules are composed by YAML; no crystal ID has special behavior. */
public interface CrystalEffectHandler {
  String path();

  void contribute(Player player, City city, ConfigurationSection module, EffectPlan plan);
}
