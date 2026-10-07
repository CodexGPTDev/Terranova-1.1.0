package dev.marco035xd.terranova;

import static org.junit.jupiter.api.Assertions.*;

import dev.marco035xd.terranova.crystal.EffectPlan;
import dev.marco035xd.terranova.service.EventService;
import dev.marco035xd.terranova.util.Text;
import java.util.List;
import org.bukkit.boss.BarStyle;
import org.junit.jupiter.api.Test;

class ConfigurationMathTest {
  @Test
  void durationSupportsCompoundAndMilliseconds() {
    assertEquals(5430250, Text.duration("1h30m30s250ms"));
    assertEquals(1800000, Text.duration("30m"));
  }

  @Test
  void malformedOrOverflowingDurationIsRejected() {
    for (String input : List.of("0s", "-1s", "10", "1hxx", "NaN", "99999999999999999999d"))
      assertThrows(RuntimeException.class, () -> Text.duration(input));
  }

  @Test
  void moneyRejectsNanInfinityAndNegative() {
    for (double d : new double[] {Double.NaN, Double.POSITIVE_INFINITY, -1})
      assertThrows(IllegalArgumentException.class, () -> Text.money(d));
  }

  @Test
  void stackingDoesNotAccumulateAcrossRecomputations() {
    var list =
        List.of(
            new EffectPlan.Multiplier(2, "MULTIPLY"), new EffectPlan.Multiplier(1.5, "MULTIPLY"));
    assertEquals(3, EffectPlan.aggregate(list));
    assertEquals(3, EffectPlan.aggregate(list));
    assertEquals(
        2,
        EffectPlan.aggregate(
            List.of(
                new EffectPlan.Multiplier(2, "HIGHEST"),
                new EffectPlan.Multiplier(1.5, "HIGHEST"))));
    assertEquals(
        2.5,
        EffectPlan.aggregate(
            List.of(new EffectPlan.Multiplier(2, "ADD"), new EffectPlan.Multiplier(1.5, "ADD"))));
  }

  @Test
  void multiplierOverflowIsBounded() {
    assertEquals(
        1000,
        EffectPlan.aggregate(
            List.of(
                new EffectPlan.Multiplier(Double.MAX_VALUE, "MULTIPLY"),
                new EffectPlan.Multiplier(1000, "MULTIPLY"))));
  }

  @Test
  void raidOverlayExampleMapsToActualBukkitStyle() {
    assertEquals(BarStyle.SOLID, EventService.style("PROGRESS"));
    assertEquals(BarStyle.SEGMENTED_10, EventService.style("NOTCHED_10"));
  }
}
