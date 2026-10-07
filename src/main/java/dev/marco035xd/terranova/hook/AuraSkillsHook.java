package dev.marco035xd.terranova.hook;

import dev.marco035xd.terranova.TerranovaPlugin;
import org.bukkit.entity.Player;

public final class AuraSkillsHook extends DynamicEventHook {
  public AuraSkillsHook(TerranovaPlugin p) {
    super(p, "AuraSkills");
    listen(
        "dev.aurelium.auraskills.api.event.skill.XpGainEvent",
        e -> {
          Player actor = (Player) call(e, "getPlayer");
          Object skill = call(e, "getSkill");
          String id = String.valueOf(call(skill, "getId")).replaceAll("^.*[:/]", "");
          double xp = ((Number) call(e, "getAmount")).doubleValue();
          double result = xp * p.effects().xp(actor, id);
          call(e, "setAmount", Double.isFinite(result) ? result : xp);
        });
    listen(
        "dev.aurelium.auraskills.api.event.mana.ManaRegenerateEvent",
        e -> {
          Player actor = (Player) call(e, "getPlayer");
          double amount = ((Number) call(e, "getAmount")).doubleValue();
          double result = amount * p.effects().mana(actor);
          call(e, "setAmount", Double.isFinite(result) ? result : amount);
        });
  }
}
