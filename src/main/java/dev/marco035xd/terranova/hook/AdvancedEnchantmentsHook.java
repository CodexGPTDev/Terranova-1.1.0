package dev.marco035xd.terranova.hook;

import dev.marco035xd.terranova.TerranovaPlugin;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;

public final class AdvancedEnchantmentsHook extends DynamicEventHook {
  public AdvancedEnchantmentsHook(TerranovaPlugin p) {
    super(p, "AdvancedEnchantments");
    listen(
        "net.advancedplugins.ae.impl.effects.api.EffectsActivateEvent",
        e -> {
          Player actor = (Player) call(e, "getPlayer");
          String enchant = String.valueOf(call(e, "getEnchantName"));
          if (actor != null
              && p.effects().advancedDenied(actor, enchant)
              && e instanceof Cancellable c) c.setCancelled(true);
        });
  }
}
