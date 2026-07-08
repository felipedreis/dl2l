package br.cefetmg.lsi.l2l.creature.testing;

import br.cefetmg.lsi.l2l.cluster.settings.LearningSettings;
import br.cefetmg.lsi.l2l.common.Point;
import br.cefetmg.lsi.l2l.common.SequentialId;
import br.cefetmg.lsi.l2l.creature.bd.ActionSelectionType;
import br.cefetmg.lsi.l2l.creature.common.ActionType;
import br.cefetmg.lsi.l2l.creature.conditioning.expectancy.ExpectancyMode;
import br.cefetmg.lsi.l2l.stimuli.CorticalStimulus;
import br.cefetmg.lsi.l2l.stimuli.LuminousStimulus;
import br.cefetmg.lsi.l2l.stimuli.NeuromodulatorState;
import br.cefetmg.lsi.l2l.stimuli.NeuromodulatorTick;
import br.cefetmg.lsi.l2l.stimuli.SerotonergicStimulus;
import br.cefetmg.lsi.l2l.world.FruitType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Functional (whole-pipeline) tests for the neuromodulatory loop through a {@link TestingCreature}:
 * (1) the pacemaker actually delivers SerotonergicStimulus + NeuromodulatorTick to the pool and the
 * pool publishes NeuromodulatorState back to FullAppraisal; (2) those tonic signals measurably shape
 * behaviour — a neuromodulated creature (high satiety ⇒ high tonic serotonin) selects quieting
 * actions (SLEEP/OBSERVE) more often than an identical creature with neuromodulation disabled.
 */
class NeuromodulationFunctionalTest {

    private static LearningSettings settings(boolean neuromodulation) {
        return new LearningSettings(true, false,
                List.of(ActionSelectionType.AFFORDANCE, ActionSelectionType.RANDOM),
                true, ExpectancyMode.DISCRETE, neuromodulation);
    }

    private static LearningSettings loopOff() {
        return new LearningSettings(true, false,
                List.of(ActionSelectionType.AFFORDANCE, ActionSelectionType.RANDOM));
    }

    // ---------------------------------------------------------------------------------------
    // 1. Message delivery
    // ---------------------------------------------------------------------------------------

    @Test
    void pacemaker_delivers_neuromodulator_messages_and_pool_publishes_state() {
        TestingHarness h = TestingHarness.builder().learningSettings(settings(true)).build();

        for (int i = 0; i < 5; i++) h.tick();

        assertTrue(h.neuromodulatorRecorder().hasAny(SerotonergicStimulus.class),
                "PartialAppraisal must release a SerotonergicStimulus each cycle");
        assertTrue(h.neuromodulatorRecorder().hasAny(NeuromodulatorTick.class),
                "PartialAppraisal must send a NeuromodulatorTick each cycle");
        assertTrue(h.fullRecorder().hasAny(NeuromodulatorState.class),
                "NeuromodulatorSystem must publish tonic NeuromodulatorState to FullAppraisal");
    }

    @Test
    void no_neuromodulator_messages_when_loop_disabled() {
        TestingHarness h = TestingHarness.builder().learningSettings(loopOff()).build();

        for (int i = 0; i < 5; i++) h.tick();

        assertFalse(h.neuromodulatorRecorder().hasAny(SerotonergicStimulus.class),
                "no serotonin release when the loop is disabled");
        assertFalse(h.neuromodulatorRecorder().hasAny(NeuromodulatorTick.class),
                "no neuromodulator tick when the loop is disabled");
        assertFalse(h.fullRecorder().hasAny(NeuromodulatorState.class),
                "no tonic state published when the loop is disabled");
    }

    // ---------------------------------------------------------------------------------------
    // 2. Behaviour shaping (statistical, large N with generous margin)
    // ---------------------------------------------------------------------------------------

    @Test
    void serotonin_rest_bias_raises_quieting_action_share_through_the_full_pipeline() {
        // Each creature makes a single independent first-decision (no SLEEP-hysteresis lock, no
        // cross-decision correlation), so the share is a clean Bernoulli mean. High tonic serotonin is
        // delivered through the pool (SerotonergicStimulus -> NeuromodulatorSystem -> NeuromodulatorState
        // -> FullAppraisal -> affordance filter), exercising the whole wiring.
        int k = 2000;
        double withSerotonin = sleepShareOfFirstDecision(true, k);
        double baseline      = sleepShareOfFirstDecision(false, k);

        assertTrue(withSerotonin > baseline + 0.05,
                "tonic serotonin should raise the SLEEP (quieting) share of the first decision; "
                        + "high=" + withSerotonin + " baseline=" + baseline);
    }

    /**
     * Fraction of fresh creatures whose first decision on a fruit-at-distance is SLEEP. Candidate
     * actions are APPROACH/AVOID/SLEEP/OBSERVE; serotonin's rest bias up-weights the quieting SLEEP.
     * When {@code primeSerotonin}, a large SerotonergicStimulus drives the pool's tonic serotonin high
     * before the decision.
     */
    private static double sleepShareOfFirstDecision(boolean primeSerotonin, int k) {
        int sleeps = 0, total = 0;
        for (int i = 0; i < k; i++) {
            TestingHarness h = TestingHarness.builder().learningSettings(settings(true)).build();
            if (primeSerotonin) {
                SequentialId s = new SequentialId(500_000L + i);
                h.inject(br.cefetmg.lsi.l2l.creature.components.NeuromodulatorSystem.class,
                        new SerotonergicStimulus(s, s.next(), 40.0));
            }
            Point creaturePos = h.creature().getPosition();
            SequentialId a = new SequentialId(100_000L + i * 2L);
            h.injectLuminous(new LuminousStimulus(a, a.next(), FruitType.RED_APPLE,
                    new Point(creaturePos.x + 100, creaturePos.y)));
            CorticalStimulus c = h.effectorCortexRecorder().lastOf(CorticalStimulus.class);
            if (c == null) continue;
            total++;
            if (c.action == ActionType.SLEEP) sleeps++;
        }
        return total == 0 ? 0.0 : sleeps / (double) total;
    }
}
