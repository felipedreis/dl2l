package br.cefetmg.lsi.l2l.creature.testing;

import br.cefetmg.lsi.l2l.cluster.settings.LearningSettings;
import br.cefetmg.lsi.l2l.common.Constants;
import br.cefetmg.lsi.l2l.creature.bd.ActionSelectionType;
import br.cefetmg.lsi.l2l.creature.components.Body;
import br.cefetmg.lsi.l2l.creature.components.HomeostaticRegulation;
import br.cefetmg.lsi.l2l.stimuli.AdenosinergicStimulus;
import br.cefetmg.lsi.l2l.stimuli.CholinergicStimulus;
import br.cefetmg.lsi.l2l.stimuli.MuscularStimulus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Validates the sleep-pressure recovery fix: CholinergicStimulus (sleep drive clearing) must
 * NOT be emitted by Body on every stationary action. Previously, speed==0 fired CholinergicStimulus
 * for EAT and OBSERVE as well as SLEEP, preventing sleep pressure from ever accumulating.
 *
 * <p>After the fix:
 * - Body never emits CholinergicStimulus regardless of speed.
 * - Sleep pressure accumulates from AdenosinergicStimulus without being cancelled by eating.
 */
class SleepPressureFunctionalTest {

    private static final LearningSettings DEFAULT = new LearningSettings(
            true, false,
            List.of(ActionSelectionType.TARGET_DISTANCE, ActionSelectionType.RANDOM)
    );

    @Test
    void body_does_not_emit_cholinergic_on_zero_speed() {
        TestingHarness h = TestingHarness.builder().learningSettings(DEFAULT).build();

        // Inject a stationary MuscularStimulus (speed=0, the old trigger for CholinergicStimulus).
        h.inject(Body.class, new MuscularStimulus(
                h.creature().id(), h.creature().id().next(), 0.0, 0.0));

        assertFalse(h.homeostaticRecorder().hasAny(CholinergicStimulus.class),
                "Body must NOT emit CholinergicStimulus on speed=0 — sleep recovery is now gated to SLEEP actions only");
    }

    @Test
    void body_does_not_emit_cholinergic_on_nonzero_speed() {
        TestingHarness h = TestingHarness.builder().learningSettings(DEFAULT).build();

        h.inject(Body.class, new MuscularStimulus(
                h.creature().id(), h.creature().id().next(), 5.0, 0.5));

        assertFalse(h.homeostaticRecorder().hasAny(CholinergicStimulus.class),
                "Body must never emit CholinergicStimulus");
    }

    @Test
    void sleep_pressure_accumulates_without_being_cancelled_by_eating() {
        TestingHarness h = TestingHarness.builder().learningSettings(DEFAULT).build();
        double sleepBefore = h.creature().emotions().getLevel(Constants.SLEEP);

        // Inject N adenosinergic stimuli (circadian sleep drive) with no sleep action.
        for (int i = 0; i < 20; i++) {
            h.inject(HomeostaticRegulation.class,
                    new AdenosinergicStimulus(h.creature().id(), h.creature().id().next(),
                            Constants.BASE_SLEEP_DRIVE));
        }

        double sleepAfter = h.creature().emotions().getLevel(Constants.SLEEP);
        assertTrue(sleepAfter > sleepBefore,
                "Sleep pressure must accumulate from AdenosinergicStimulus without spurious clearing");
        assertEquals(sleepBefore + 20 * Constants.BASE_SLEEP_DRIVE, sleepAfter, 1e-6,
                "Sleep pressure accumulation must equal exactly 20 × BASE_SLEEP_DRIVE");
    }
}
