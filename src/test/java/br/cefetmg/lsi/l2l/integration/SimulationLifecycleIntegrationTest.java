package br.cefetmg.lsi.l2l.integration;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What a real simulation actually writes to disk: the cluster handshake, world-object
 * distribution, creature spawn, and the full sensory-motor loop end to end.
 *
 * <p>Nothing else in the suite covers this path. The component tests stop at
 * {@code TestingCreature}, which stands in for the ActorSystem, so a regression in the
 * cluster wiring, the mailbox configuration, or the Arrow write path would only surface in a
 * live run.
 *
 * <p>The whole class shares one simulation, run for a fixed window in {@code @BeforeAll} and
 * then finalized once. Finalizing is terminal for the write path (see
 * {@link SimulationIntegrationHarness#finalizeAndRead}), which is why rate measurement lives
 * in a separate class with its own harness rather than here.
 */
class SimulationLifecycleIntegrationTest {

    /**
     * How many cognitive cycles to let accumulate before finalizing - a few seconds at the
     * ~30 Hz pacemaker rate, enough for every stage of the loop to have written something
     * and for the cycles-to-decisions ratio to be a ratio rather than a coincidence.
     */
    private static final int CYCLES_REQUIRED = 400;

    @TempDir
    static Path saveDir;

    private static SimulationIntegrationHarness harness;

    @BeforeAll
    static void bootSimulation() {
        harness = SimulationIntegrationHarness.boot(
                "simulations/integration_single_creature.conf", "", saveDir);
        harness.awaitCond(() -> harness.totalCognitiveCycles() > CYCLES_REQUIRED,
                Duration.ofSeconds(60),
                "the creature did not accumulate " + CYCLES_REQUIRED + " cognitive cycles");
    }

    @AfterAll
    static void shutdown() {
        if (harness != null) harness.close();
    }

    private static int rows(Map<String, List<Object>> table) {
        return table.isEmpty() ? 0 : table.values().iterator().next().size();
    }

    @Test
    void the_creature_is_born_and_recorded() throws Exception {
        assertTrue(rows(harness.finalizeAndRead("creature_state")) >= 1,
                "a creature_state row must be written at birth");
    }

    @Test
    void the_full_sensory_motor_loop_reaches_persistence() throws Exception {
        // Perception -> appraisal -> action -> movement, each stage evidenced by its own
        // table. Asserting on all four together is what makes this a loop test rather than
        // four unit tests: a break anywhere in the chain empties the tables downstream of it.
        assertTrue(rows(harness.finalizeAndRead("object_seen_state")) > 0,
                "the collision detector must drive the eye - no object_seen_state rows means "
                        + "perception never reached the creature");
        assertTrue(rows(harness.finalizeAndRead("behavioural_efficiency_state")) > 0,
                "PartialAppraisal must record a cycle");
        assertTrue(rows(harness.finalizeAndRead("chosen_action_state")) > 0,
                "FullAppraisal must select actions");
        assertTrue(rows(harness.finalizeAndRead("body_state")) > 0,
                "EffectorCortex must drive the body");
    }

    @Test
    void every_cognitive_cycle_produces_exactly_one_decision() throws Exception {
        // PartialAppraisal writes one behavioural_efficiency_state per cycle and hands one
        // EmotionalStimulus to FullAppraisal, which writes one chosen_action_state per
        // decision. The two counts tracking each other is the invariant that a cycle is a
        // single appraise-and-act unit; they would diverge if a cycle ever fanned out into
        // several decisions or dropped one. Before issue #85 both counts were inflated ~9x
        // together, so this invariant held then too - it guards the cycle's internal shape,
        // not its rate.
        int cycles = rows(harness.finalizeAndRead("behavioural_efficiency_state"));
        int decisions = rows(harness.finalizeAndRead("chosen_action_state"));

        assertTrue(cycles > 0 && decisions > 0);
        assertEquals(1.0, decisions / (double) cycles, 0.02,
                "decisions per cognitive cycle should be 1 (cycles=" + cycles
                        + ", decisions=" + decisions + ")");
    }

    @Test
    void hunger_both_accrues_and_is_relieved_by_eating() throws Exception {
        // Cycles that ran but did nothing would satisfy every count above and still leave a
        // creature that never grows hungry, never forages and never dies.
        //
        // Both directions are asserted, and neither alone would do. Hunger rising shows
        // PartialAppraisal.tickMetabolicPacemaker running (once per cycle, dt-weighted);
        // hunger falling shows the creature actually reached a fruit and ate it, which
        // exercises the mouth/holder/EnergeticStimulus path that no count above touches. In
        // a world with 25 fruit the net change over a run is not predictable in either
        // direction, so asserting a monotonic trend would be asserting the world, not the
        // creature.
        List<Object> hunger = harness.finalizeAndRead("emotional_state").get("hunger_arausal");
        assertNotNull(hunger);
        assertTrue(hunger.size() > 1, "not enough emotional_state rows to see a trend");

        boolean rose = false;
        boolean fell = false;
        for (int i = 1; i < hunger.size(); i++) {
            double prev = ((Number) hunger.get(i - 1)).doubleValue();
            double curr = ((Number) hunger.get(i)).doubleValue();
            if (curr > prev) rose = true;
            if (curr < prev) fell = true;
        }

        assertTrue(rose, "hunger never accrued - the metabolic pacemaker is not running");
        assertTrue(fell, "hunger was never relieved - the creature never ate, so the "
                + "mouth -> holder -> EnergeticStimulus path is broken or unreachable");
    }
}
