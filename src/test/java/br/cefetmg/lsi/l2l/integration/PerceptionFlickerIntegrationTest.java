package br.cefetmg.lsi.l2l.integration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Issue #85 finding 2 - the perception flicker - measured on data a real simulation
 * persisted.
 *
 * <p>The defect: a physically stationary fruit was presented to the emotional and
 * action-selection pipeline as appearing and vanishing within milliseconds, because empty
 * heartbeat cycles interleaved with perception-driven ones. The issue measured it two ways,
 * as 25.1% of consecutive cycle pairs crossing the empty/non-empty boundary, and as
 * <em>~66 flips per second</em>.
 *
 * <p>Those two are not interchangeable, and the tests assert the second. The fraction of
 * consecutive pairs is a per-cycle quantity, so it counts pairs several times as often at
 * several times the cycle rate; fixing the rate therefore moves its denominator as much as
 * its numerator, and it understates the improvement badly. One run of these two arms
 * measured 28.9% of pairs at 152.5 Hz un-gated against 15.2% at 30.5 Hz gated - the fraction
 * roughly halves, while flips per second (how often the creature's percept actually changes,
 * which is what the downstream pipeline experiences) falls almost tenfold, 44.1 to 4.6. The
 * per-second figure describes the creature's experience, so it is the one asserted.
 * (Issue #85's acceptance criterion is phrased in terms of the fraction; that wording should
 * be updated.)
 *
 * <p>The assertion compares two arms <em>in the same world</em> rather than against an
 * absolute threshold. How often objects genuinely enter and leave a moving creature's visual
 * field depends entirely on object density, world size and how much the creature sleeps, so
 * a threshold tuned against one config would say nothing about the fix. Both arms come from
 * the same build, differing only in {@code learningSettings.tickGatedCognition} - which is
 * also how the p85 experiment's baseline arm works, so this exercises that switch on the
 * path the experiment will use.
 */
class PerceptionFlickerIntegrationTest {

    private static final String WORLD = "simulations/integration_single_creature.conf";

    /**
     * Cycles to accumulate before finalizing. The flip rate is a ratio over consecutive
     * pairs, so it needs enough pairs to be stable rather than a handful of transitions.
     */
    private static final int CYCLES_REQUIRED = 400;

    /** Wall-clock window the cycle rate is averaged over, to convert flips/cycle to flips/s. */
    private static final Duration MEASURE_WINDOW = Duration.ofSeconds(4);

    private record Flicker(double fractionOfPairs, double cycleRateHz) {
        double flipsPerSecond() {
            return fractionOfPairs * cycleRateHz;
        }

        @Override
        public String toString() {
            return String.format("%.1f flips/s (%.1f%% of pairs at %.1f Hz)",
                    flipsPerSecond(), fractionOfPairs * 100, cycleRateHz);
        }
    }

    @Test
    void tick_gating_substantially_reduces_how_often_perception_changes(
            @TempDir Path gatedDir, @TempDir Path baselineDir) throws Exception {
        // Sequentially, never concurrently: two live simulations in one JVM compete for the
        // same dispatcher threads, which changes both the cycle rate and how detector replies
        // interleave - the very things being measured.
        Flicker gated = measure("", gatedDir);
        Flicker baseline =
                measure("simulation.learningSettings.tickGatedCognition = false", baselineDir);

        // Without this the comparison below could pass because both arms are quiet.
        assertTrue(baseline.flipsPerSecond() > 20,
                "the baseline arm should reproduce the pre-#85 flicker (issue #85 measured "
                        + "~66 flips/s); got " + baseline);

        assertTrue(gated.flipsPerSecond() < baseline.flipsPerSecond() * 0.5,
                "tick-gating should substantially reduce how often the percept changes, but "
                        + "gated=" + gated + " vs baseline=" + baseline);
    }

    @Test
    void a_tick_gated_cycle_records_what_it_perceived(@TempDir Path dir) throws Exception {
        // perceivedobjects is the column issue #85 added and the p85 analysis reads. Assert
        // it survives the whole write path with sane values, rather than trusting the schema
        // test - which only checks that the column exists.
        try (SimulationIntegrationHarness h = runUntilEnoughCycles("", dir)) {
            List<Object> perceived = h.finalizeAndRead("behavioural_efficiency_state")
                    .get("perceivedobjects");

            assertNotNull(perceived, "behavioural_efficiency_state must carry perceivedobjects");
            assertTrue(perceived.size() > 100,
                    "not enough cycles recorded: " + perceived.size());
            assertTrue(perceived.stream().allMatch(v -> ((Number) v).intValue() >= 0),
                    "a perception count can never be negative");
            assertTrue(perceived.stream().anyMatch(v -> ((Number) v).intValue() > 0),
                    "a creature in a world with 25 fruit must perceive something at least once");
        }
    }

    /**
     * Runs one arm and returns its flicker. The cycle rate is measured on the same steady
     * state the perception record comes from, so the two combine into a flips-per-second
     * figure that is comparable across arms running at different rates.
     */
    private static Flicker measure(String overrides, Path dir) throws Exception {
        try (SimulationIntegrationHarness h = runUntilEnoughCycles(overrides, dir)) {
            double hz = h.measureCycleRateHz(MEASURE_WINDOW);
            List<Object> perceived = h.finalizeAndRead("behavioural_efficiency_state")
                    .get("perceivedobjects");
            assertNotNull(perceived);
            assertTrue(perceived.size() > 100,
                    "not enough cycles to measure a flip rate: " + perceived.size());
            return new Flicker(flipFraction(perceived), hz);
        }
    }

    private static SimulationIntegrationHarness runUntilEnoughCycles(String overrides, Path dir) {
        SimulationIntegrationHarness h = SimulationIntegrationHarness.boot(WORLD, overrides, dir);
        h.awaitCond(() -> h.totalCognitiveCycles() > CYCLES_REQUIRED,
                Duration.ofSeconds(60),
                "the creature did not accumulate " + CYCLES_REQUIRED + " cognitive cycles");
        return h;
    }

    /** Fraction of consecutive cycle pairs where "perceived something" changes. */
    private static double flipFraction(List<Object> perceivedObjects) {
        int flips = 0;
        for (int i = 1; i < perceivedObjects.size(); i++) {
            boolean prev = ((Number) perceivedObjects.get(i - 1)).intValue() > 0;
            boolean curr = ((Number) perceivedObjects.get(i)).intValue() > 0;
            if (prev != curr) flips++;
        }
        return flips / (double) (perceivedObjects.size() - 1);
    }
}
