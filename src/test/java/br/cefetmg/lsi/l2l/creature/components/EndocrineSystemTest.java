package br.cefetmg.lsi.l2l.creature.components;

import br.cefetmg.lsi.l2l.common.Constants;
import br.cefetmg.lsi.l2l.common.SequentialId;
import br.cefetmg.lsi.l2l.creature.testing.TestingHarness;
import br.cefetmg.lsi.l2l.stimuli.CortisolStimulus;
import br.cefetmg.lsi.l2l.stimuli.EndocrineTick;
import br.cefetmg.lsi.l2l.stimuli.EndocrineState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for EndocrineSystem: saturating-feedback accumulation, EndocrineTick-driven decay,
 * STRESS affect activation, and bounded steady-state under constant input.
 */
public class EndocrineSystemTest {

    private static final SequentialId SID = new SequentialId(888L);

    private static CortisolStimulus cortisol(double magnitude) {
        return new CortisolStimulus(SID, SID.next(), magnitude);
    }

    private static EndocrineTick tick(double phase) {
        return new EndocrineTick(SID, SID.next(), phase);
    }

    private static EndocrineState lastState(TestingHarness h) {
        EndocrineState s = h.fullRecorder().lastOf(EndocrineState.class);
        assertNotNull(s, "EndocrineSystem must publish an EndocrineState to FullAppraisal after each update");
        return s;
    }

    @Test
    void cortisol_accumulates_from_stimulus() {
        TestingHarness h = TestingHarness.builder().build();

        // From an empty pool: magnitude / (1 + k * 0) = magnitude
        h.inject(EndocrineSystem.class, cortisol(1.0));
        assertEquals(1.0, lastState(h).cortisolTonic, 1e-9);
    }

    @Test
    void cortisol_accumulates_with_feedback_across_stimuli() {
        TestingHarness h = TestingHarness.builder().build();

        // First injection: cortisol = 0 + 1.0/(1+k*0) = 1.0
        h.inject(EndocrineSystem.class, cortisol(1.0));
        // Second injection: cortisol = 1.0 + 1.0/(1+k*1.0) = 1.0 + 0.5 = 1.5
        h.inject(EndocrineSystem.class, cortisol(1.0));
        double expected = 1.0 + 1.0 / (1.0 + Constants.CORTISOL_FEEDBACK_GAIN * 1.0);
        assertEquals(expected, lastState(h).cortisolTonic, 1e-9);
    }

    @Test
    void cortisol_decays_via_endocrine_tick() {
        TestingHarness h = TestingHarness.builder().build();

        h.inject(EndocrineSystem.class, cortisol(5.0));
        double initial = lastState(h).cortisolTonic;

        // Decay happens via EndocrineTick, not CortisolStimulus. After 100 ticks at DECAY=0.998,
        // ~81% of the initial pool remains (circadian synthesis adds a small amount back).
        for (int i = 0; i < 100; i++) {
            h.inject(EndocrineSystem.class, tick(0.0));
        }
        double remaining = lastState(h).cortisolTonic;
        assertTrue(remaining > initial * 0.75,
                "cortisol should still be above 75% of initial after 100 EndocrineTicks; was " + remaining);
        assertTrue(remaining < initial,
                "cortisol should have decreased after 100 EndocrineTicks; was " + remaining);
    }

    @Test
    void stress_activates_above_cortisol_threshold() {
        TestingHarness h = TestingHarness.builder().build();

        double needed = Constants.CORTISOL_STRESS_THRESHOLD + 1.0;
        h.inject(EndocrineSystem.class, cortisol(needed));

        assertTrue(h.creature().emotions().getLevel(Constants.STRESS) > 0,
                "STRESS affect should activate once cortisol exceeds CORTISOL_STRESS_THRESHOLD");
        assertTrue(lastState(h).stressLevel > 0,
                "published EndocrineState.stressLevel should be positive");
    }

    @Test
    void stress_at_floor_below_cortisol_threshold() {
        TestingHarness h = TestingHarness.builder().build();

        h.inject(EndocrineSystem.class, cortisol(Constants.CORTISOL_STRESS_THRESHOLD - 0.5));

        assertEquals(Constants.MIN_AROUSAL_LEVEL, lastState(h).stressLevel, 1e-9,
                "STRESS should stay at MIN_AROUSAL_LEVEL while cortisol is below threshold");
        assertEquals(Constants.MIN_AROUSAL_LEVEL, h.creature().emotions().getLevel(Constants.STRESS), 1e-9);
    }

    @Test
    void cortisol_bounded_at_steady_state_under_constant_input() {
        TestingHarness h = TestingHarness.builder().build();

        // Drive constant cortisol input + EndocrineTick every iteration. Without negative feedback
        // cortisol would diverge; with feedback it must converge to a finite ceiling.
        // Theoretical steady state (input=0.1, baseline=0.003, k=1): c* ≈ 6.7
        double ceiling = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < 2000; i++) {
            h.inject(EndocrineSystem.class, cortisol(0.1));
            h.inject(EndocrineSystem.class, tick(0.0));
            ceiling = Math.max(ceiling, lastState(h).cortisolTonic);
        }
        assertTrue(ceiling < 10.0,
                "cortisol must converge to a finite ceiling under constant input; reached " + ceiling);
        double last = lastState(h).cortisolTonic;
        assertTrue(Math.abs(last - ceiling) < 0.5,
                "cortisol should have converged (near ceiling) after 2000 cycles; last=" + last + " ceiling=" + ceiling);
    }
}
