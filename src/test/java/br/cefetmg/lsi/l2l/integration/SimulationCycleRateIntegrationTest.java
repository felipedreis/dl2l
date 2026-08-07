package br.cefetmg.lsi.l2l.integration;

import br.cefetmg.lsi.l2l.common.Constants;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Issue #85 finding 1 - the cognitive cycle rate - asserted against a running simulation.
 *
 * <p>This is a wall-clock property of the real system and cannot be expressed against
 * {@code TestingCreature}: that harness has no scheduler (its {@code tick()} is the test
 * calling a method) and no collision detector, so "how often does a cycle actually run" and
 * "does the detector's round trip drive cycles of its own" are both unanswerable there. That
 * is why a ~9x rate overshoot survived a green suite.
 *
 * <p>Two conventions keep these honest on a shared CI runner. Assertions are <em>bands</em>,
 * never point values - the claim is an order of magnitude (~30 Hz rather than ~260 Hz), so a
 * band wide enough to absorb a loaded runner still fails decisively on the pre-fix
 * behaviour. And simulations run <em>one at a time</em>: two live simulations in one JVM
 * compete for the same dispatcher threads, which both distorts a rate measurement and (seen
 * while writing these) can starve the second one's startup handshake outright.
 */
class SimulationCycleRateIntegrationTest {

    /** Wall-clock window each rate measurement averages over. */
    private static final Duration MEASURE_WINDOW = Duration.ofSeconds(4);

    private static final String DENSE_WORLD = "simulations/integration_single_creature.conf";
    private static final String EMPTY_WORLD = "simulations/integration_empty_world.conf";

    @Test
    void cycle_rate_is_the_tick_rate_regardless_of_perception_load(
            @TempDir Path denseDir, @TempDir Path emptyDir) throws Exception {
        // A world with 25 objects in 400x400 keeps something in sensory range almost always;
        // the empty world keeps the detector silent. Before the fix these differed by roughly
        // an order of magnitude, because every detector reply was a cycle - the dense world
        // measured 259-298 Hz against a nominal 30 (issue #85, p84 data) while an empty one
        // would fall back to the bare heartbeat. Now one local clock drives both.
        double denseHz;
        try (SimulationIntegrationHarness h = SimulationIntegrationHarness.boot(DENSE_WORLD, "", denseDir)) {
            denseHz = h.measureCycleRateHz(MEASURE_WINDOW);
        }

        double emptyHz;
        try (SimulationIntegrationHarness h = SimulationIntegrationHarness.boot(EMPTY_WORLD, "", emptyDir)) {
            emptyHz = h.measureCycleRateHz(MEASURE_WINDOW);
        }

        assertAtTickRate(denseHz, "world with objects in sensory range");
        // Also the liveness guarantee the old unconditional heartbeat provided, and the one
        // issue #85's own proposal put at risk by moving the cycle driver to a round trip
        // with a cluster-wide singleton: a creature with nothing to perceive keeps cognizing,
        // at the same rate as any other.
        assertAtTickRate(emptyHz, "empty world");

        assertEquals(1.0, denseHz / emptyHz, 0.5,
                "cycle rate must not depend on how much is in sensory range (dense="
                        + denseHz + " Hz, empty=" + emptyHz + " Hz)");
    }

    @Test
    void cognition_survives_the_collision_detector_going_away(@TempDir Path dir) throws Exception {
        // Issue #85's second acceptance criterion, and the concrete reason the cycle driver
        // stays local. Stopping the detector removes all perception; if it were also the
        // cycle driver - as the issue's proposed fix would have made it - the creature would
        // silently stop cognizing, metabolizing and dying.
        try (SimulationIntegrationHarness h = SimulationIntegrationHarness.boot(DENSE_WORLD, "", dir)) {
            double withDetector = h.measureCycleRateHz(MEASURE_WINDOW);
            h.stopActor("collisionDetector");
            double withoutDetector = h.measureCycleRateHz(MEASURE_WINDOW);

            assertAtTickRate(withoutDetector, "collision detector stopped");
            assertEquals(1.0, withoutDetector / withDetector, 0.5,
                    "losing the detector must cost perception, not the cognitive cycle (with="
                            + withDetector + " Hz, without=" + withoutDetector + " Hz)");
        }
    }

    @Test
    void baseline_arm_reproduces_the_pre_fix_rate(@TempDir Path dir) throws Exception {
        // The control. It proves the assertions above are not vacuous - the same world, same
        // build and same window really can produce the runaway rate - and it guards the
        // tickGatedCognition switch that the p85 experiment's baseline arm depends on. A
        // silently-broken switch would make that arm measure the fix against itself.
        try (SimulationIntegrationHarness h = SimulationIntegrationHarness.boot(
                DENSE_WORLD, "simulation.learningSettings.tickGatedCognition = false", dir)) {

            double hz = h.measureCycleRateHz(MEASURE_WINDOW);

            assertTrue(hz > Constants.TARGET_CYCLE_HZ * 2.0,
                    "with tick-gating off the cycle rate should run well above the pacemaker "
                            + "(the pre-#85 behaviour); got " + hz + " Hz");
        }
    }

    /**
     * Asserts a measured rate sits in a wide band around {@link Constants#TARGET_CYCLE_HZ}.
     * The lower bound catches a creature that stopped cognizing; the upper bound catches
     * perception driving cycles again.
     */
    private static void assertAtTickRate(double hz, String context) {
        assertTrue(hz > Constants.TARGET_CYCLE_HZ * 0.5,
                "cognitive cycle rate collapsed (" + context + "): " + hz
                        + " Hz, expected ~" + Constants.TARGET_CYCLE_HZ);
        assertTrue(hz < Constants.TARGET_CYCLE_HZ * 2.0,
                "cognitive cycle rate is running away from the pacemaker (" + context + "): "
                        + hz + " Hz against a nominal " + Constants.TARGET_CYCLE_HZ
                        + " - perception is driving cycles again");
    }
}
