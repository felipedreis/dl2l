package br.cefetmg.lsi.l2l.creature.components;

import br.cefetmg.lsi.l2l.common.Constants;
import br.cefetmg.lsi.l2l.common.SequentialId;
import br.cefetmg.lsi.l2l.creature.testing.TestingHarness;
import br.cefetmg.lsi.l2l.stimuli.CortisolStimulus;
import br.cefetmg.lsi.l2l.stimuli.EndocrineState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the EndocrineSystem leaky integrator: cortisol accumulation, slow decay,
 * and activation of the STRESS affect above the cortisol threshold.
 */
public class EndocrineSystemTest {

    private static final SequentialId SID = new SequentialId(888L);

    private static CortisolStimulus cortisol(double magnitude) {
        return new CortisolStimulus(SID, SID.next(), magnitude);
    }

    private static EndocrineState lastState(TestingHarness h) {
        // EndocrineSystem publishes EndocrineState to fullAppraisal(), so it appears there.
        EndocrineState s = h.fullRecorder().lastOf(EndocrineState.class);
        assertNotNull(s, "EndocrineSystem must publish an EndocrineState to FullAppraisal after each update");
        return s;
    }

    @Test
    void cortisol_accumulates_from_stimulus() {
        TestingHarness h = TestingHarness.builder().build();

        h.inject(EndocrineSystem.class, cortisol(1.0));
        assertEquals(1.0, lastState(h).cortisolTonic, 1e-9);
    }

    @Test
    void cortisol_accumulates_additively_across_stimuli() {
        TestingHarness h = TestingHarness.builder().build();

        h.inject(EndocrineSystem.class, cortisol(1.0));
        h.inject(EndocrineSystem.class, cortisol(1.0));
        double expected = 1.0 * Constants.CORTISOL_DECAY + 1.0;
        assertEquals(expected, lastState(h).cortisolTonic, 1e-9);
    }

    @Test
    void cortisol_decays_slowly() {
        TestingHarness h = TestingHarness.builder().build();

        h.inject(EndocrineSystem.class, cortisol(5.0));
        double initial = lastState(h).cortisolTonic;

        // After 100 batches the decay should be gradual (CORTISOL_DECAY=0.998 → ~82% remains).
        for (int i = 0; i < 100; i++) {
            h.inject(EndocrineSystem.class, cortisol(0.0));
        }
        double remaining = lastState(h).cortisolTonic;
        assertTrue(remaining > initial * 0.75,
                "cortisol should still be above 75% of initial after 100 cycles; was " + remaining);
    }

    @Test
    void stress_activates_above_cortisol_threshold() {
        TestingHarness h = TestingHarness.builder().build();

        // Inject enough cortisol to exceed CORTISOL_STRESS_THRESHOLD in a single shot
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

        // Small cortisol injection — stays below threshold; STRESS should stay at its minimum floor.
        h.inject(EndocrineSystem.class, cortisol(Constants.CORTISOL_STRESS_THRESHOLD - 0.5));

        assertEquals(Constants.MIN_AROUSAL_LEVEL, lastState(h).stressLevel, 1e-9,
                "STRESS should stay at MIN_AROUSAL_LEVEL (emotion floor) while cortisol is below threshold");
        assertEquals(Constants.MIN_AROUSAL_LEVEL, h.creature().emotions().getLevel(Constants.STRESS), 1e-9);
    }
}
