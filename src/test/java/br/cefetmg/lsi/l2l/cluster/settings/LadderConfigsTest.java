package br.cefetmg.lsi.l2l.cluster.settings;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.junit.jupiter.api.Test;

import java.io.File;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The ladder's simulation configs (docs/experiments/arbiter_ladder_recipe.md section 3): A2, A3 and A5 differ only in the
 * episodic flags, and A5 differs from A3 only in the fixed-order switch.
 */
class LadderConfigsTest {

    private static LearningSettings load(String name) {
        Config config = ConfigFactory.parseFile(new File("simulations/" + name)).resolve();
        return new Simulation(config).getLearningSettings();
    }

    @Test
    void a3IsA2PlusTheEpisodicControllerOnly() {
        LearningSettings a2 = load("p107_gate2_a2.conf");
        LearningSettings a3 = load("arbiter_a3.conf");
        assertFalse(a2.isEpisodicEstimatorEnabled());
        assertTrue(a3.isEpisodicEstimatorEnabled());
        assertFalse(a3.isArbiterFixedOrder());
        assertEquals(0.5, a3.getEpisodicKeyLambda(), 0.0);
        assertSameApartFromEpisodic(a2, a3);
    }

    @Test
    void a5IsA3WithTheFixedOrderSwitchOnly() {
        LearningSettings a3 = load("arbiter_a3.conf");
        LearningSettings a5 = load("arbiter_a5.conf");
        assertTrue(a5.isEpisodicEstimatorEnabled());
        assertTrue(a5.isArbiterFixedOrder());
        assertEquals(a3.getEpisodicKeyLambda(), a5.getEpisodicKeyLambda(), 0.0);
        assertSameApartFromEpisodic(a3, a5);
    }

    @Test
    void everyArmWithATrackerUsesTheSettledThresholdsAndTheClassicTendencies() {
        for (String name : new String[] {"p107_gate2_a1.conf", "p107_gate2_a2.conf", "arbiter_a3.conf", "arbiter_a5.conf"}) {
            LearningSettings s = load(name);
            assertEquals(0.4, s.getFocusThetaOn(), 0.0, name);
            assertEquals(0.15, s.getFocusThetaOff(), 0.0, name);
            assertEquals(LearningSettings.ActionTendencySet.CAMPOS, s.getActionTendencySet(), name);
            assertTrue(s.isActionTendencyEnabled(), name);
            assertFalse(s.isNeuromodulationEnabled(), name);
            assertFalse(s.isExpectancyEnabled(), name);
            assertFalse(s.isOrexinEnabled(), name);
            assertFalse(s.isEndocrineEnabled(), name);
        }
    }

    @Test
    void theArmsOfThePlantsWorldAreTheSameArmsWithPlantsNoSleepLockAndNoTendency() {
        String[][] pairs = {{"p107_gate2_a2.conf", "p117_plants_a2.conf"}, {"arbiter_a3.conf", "p117_plants_a3.conf"},
                {"arbiter_a5.conf", "p117_plants_a5.conf"}};
        for (String[] p : pairs) {
            Simulation base = sim(p[0]);
            Simulation plants = sim(p[1]);
            LearningSettings b = base.getLearningSettings();
            LearningSettings x = plants.getLearningSettings();
            assertTrue(b.isSleepHysteresisEnabled(), p[0]);
            assertFalse(x.isSleepHysteresisEnabled(), p[1]);
            assertTrue(b.isActionTendencyEnabled(), p[0]);
            assertFalse(x.isActionTendencyEnabled(), p[1]);
            assertSameApartFromEpisodic(b, x, false);
            assertEquals(b.isEpisodicEstimatorEnabled(), x.isEpisodicEstimatorEnabled(), p[1]);
            assertEquals(b.isArbiterFixedOrder(), x.isArbiterFixedOrder(), p[1]);
            java.util.Map<String, Integer> w = new java.util.HashMap<>();
            plants.getWorldObjectSettings().forEach(o -> w.put(o.getType().name(), o.getQuantity()));
            assertEquals(java.util.Map.of("RED_APPLE", 100, "GREEN_APPLE", 100, "GRAY_APPLE", 300, "CACTUS", 140, "ALOE", 140), w, p[1]);
        }
    }

    @Test
    void theFactorialArmsCrossTheTwoPriorsOnTheSameWorldAndCap() {
        for (String arm : new String[] {"a3", "a5"}) {
            LearningSettings base = load(arm.equals("a3") ? "arbiter_a3.conf" : "arbiter_a5.conf");
            for (int lock = 0; lock <= 1; lock++) {
                for (int tend = 0; tend <= 1; tend++) {
                    String name = "p117_fact_" + arm + "_L" + lock + "T" + tend + ".conf";
                    Simulation sim = sim(name);
                    LearningSettings x = sim.getLearningSettings();
                    assertEquals(lock == 1, x.isSleepHysteresisEnabled(), name);
                    assertEquals(tend == 1, x.isActionTendencyEnabled(), name);
                    assertEquals(base.isEpisodicEstimatorEnabled(), x.isEpisodicEstimatorEnabled(), name);
                    assertEquals(base.isArbiterFixedOrder(), x.isArbiterFixedOrder(), name);
                    assertEquals(60, sim.getMaxRuntimeMinutes(), name);
                    java.util.Map<String, Integer> w = new java.util.HashMap<>();
                    sim.getWorldObjectSettings().forEach(o -> w.put(o.getType().name(), o.getQuantity()));
                    assertEquals(java.util.Map.of("RED_APPLE", 100, "GREEN_APPLE", 100, "GRAY_APPLE", 300, "CACTUS", 140, "ALOE", 140), w, name);
                    assertSameApartFromEpisodic(base, x, false);
                }
            }
        }
    }

    private static Simulation sim(String name) {
        return new Simulation(ConfigFactory.parseFile(new File("simulations/" + name)).resolve());
    }

    private static void assertSameApartFromEpisodic(LearningSettings x, LearningSettings y) {
        assertSameApartFromEpisodic(x, y, true);
    }

    private static void assertSameApartFromEpisodic(LearningSettings x, LearningSettings y, boolean sameTendency) {
        assertEquals(x.getActionSelector(), y.getActionSelector());
        assertEquals(x.getEnabledFilters(), y.getEnabledFilters());
        assertEquals(x.isFocalDriveEnabled(), y.isFocalDriveEnabled());
        assertEquals(x.isOperantEstimatorEnabled(), y.isOperantEstimatorEnabled());
        assertEquals(x.isOperantLearningEnabled(), y.isOperantLearningEnabled());
        assertEquals(x.getFocusThetaOn(), y.getFocusThetaOn(), 0.0);
        assertEquals(x.getFocusThetaOff(), y.getFocusThetaOff(), 0.0);
        assertEquals(x.getActionTendencySet(), y.getActionTendencySet());
        if (sameTendency) {
            assertEquals(x.isActionTendencyEnabled(), y.isActionTendencyEnabled());
        }
    }
}
