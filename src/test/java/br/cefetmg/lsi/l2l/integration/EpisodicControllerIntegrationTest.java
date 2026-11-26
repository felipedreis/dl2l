package br.cefetmg.lsi.l2l.integration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A3 and A5 through the real {@code CreatureActor} (issues #111-#116): the TypedActor facade of the episodic store is created, the arbiter
 * consults it, and the decisions are persisted with the budget and the novelty bonus. The in-process harness of the functional tests
 * bypasses {@code CreatureActor}, so the facade wiring is only exercised here.
 */
public class EpisodicControllerIntegrationTest {

    private static final String A3 = String.join("\n",
            "simulation.learningSettings.circadianEnabled = false",
            "simulation.learningSettings.enabledFilters = [TARGET_DISTANCE]",
            "simulation.learningSettings.actionTendencyEnabled = true",
            "simulation.learningSettings.focalDriveEnabled = true",
            "simulation.learningSettings.operantEstimatorEnabled = true",
            "simulation.learningSettings.actionSelector = ARBITER",
            "simulation.learningSettings.focusThetaOn = 0.02",
            "simulation.learningSettings.focusThetaOff = 0.005",
            "simulation.learningSettings.episodicEstimatorEnabled = true");

    private static void runAndCheck(Path saveDir, String overrides) throws Exception {
        try (SimulationIntegrationHarness h = SimulationIntegrationHarness.boot(
                "simulations/integration_single_creature.conf", overrides, saveDir)) {
            Thread.sleep(6000);
            Map<String, List<Object>> decisions = h.finalizeAndRead("arbitration_decision_state");
            assertFalse(decisions.isEmpty(), "the arbiter decided: the creature booted with the episodic facade");
            int rows = decisions.get("seq").size();
            assertTrue(rows > 10, "decisions persisted: " + rows);
            assertTrue(decisions.containsKey("novelty_bonus"), "the novelty bonus is in the table");
            assertTrue(decisions.containsKey("episodic_q"), "the episodic controller has its columns");
            for (Object c : decisions.get("deciding_controller")) {
                assertTrue(List.of("OPERANT", "EPISODIC").contains(c), "deciding controller " + c);
            }
            for (Object e : decisions.get("efficiency")) {
                assertTrue(((Number) e).doubleValue() >= 0 && ((Number) e).doubleValue() <= 1.0 + 1e-9, "efficiency " + e);
            }
        }
    }

    /** The plants world's objects and the hysteresis switch off, on the integration world (400 x 400, one creature). */
    private static final String PLANTS = String.join("\n",
            "simulation.worldObjectSettings = [",
            "  { objectType = \"RED_APPLE\", quantity = 10 }, { objectType = \"GRAY_APPLE\", quantity = 10 },",
            "  { objectType = \"CACTUS\", quantity = 15 }, { objectType = \"ALOE\", quantity = 15 } ]",
            "simulation.learningSettings.sleepHysteresisEnabled = false",
            "simulation.learningSettings.actionTendencyEnabled = false");

    @Test
    void a3RunsInAWorldWithPlantsNoSleepLockAndNoTendency(@TempDir Path saveDir) throws Exception {
        runAndCheck(saveDir, A3 + "\n" + PLANTS);
    }

    @Test
    void a3BootsWithTheEpisodicControllerAndDecides(@TempDir Path saveDir) throws Exception {
        runAndCheck(saveDir, A3);
    }

    @Test
    void a5BootsWithTheFixedOrderRuleAndDecides(@TempDir Path saveDir) throws Exception {
        runAndCheck(saveDir, A3 + "\nsimulation.learningSettings.arbiterFixedOrder = true");
    }
}
