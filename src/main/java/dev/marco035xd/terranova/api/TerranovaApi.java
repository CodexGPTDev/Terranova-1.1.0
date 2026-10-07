package dev.marco035xd.terranova.api;

import dev.marco035xd.terranova.crystal.*;
import dev.marco035xd.terranova.model.*;
import dev.marco035xd.terranova.service.*;
import java.util.*;
import org.bukkit.Location;
import org.bukkit.entity.Player;

public interface TerranovaApi {
  record CityView(
      UUID id,
      UUID owner,
      String name,
      int level,
      Cuboid bounds,
      Set<UUID> members,
      Map<String, FlagRule> baseFlags,
      boolean shieldActive) {}

  Optional<CityView> cityAt(Location location);

  Collection<CityView> cities();

  ProtectionDecisionService.Decision decision(Player actor, Location at, String flag);

  CrystalEffectRegistry crystalRegistry();

  void registerGuard(ProtectionDecisionService.ExternalGuard guard);

  void registerEventType(String type, EventService.EventFactory factory);
}
