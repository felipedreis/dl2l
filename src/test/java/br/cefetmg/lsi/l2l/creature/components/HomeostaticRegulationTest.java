package br.cefetmg.lsi.l2l.creature.components;

import br.cefetmg.lsi.l2l.cluster.settings.LearningSettings;
import br.cefetmg.lsi.l2l.common.Constants;
import br.cefetmg.lsi.l2l.common.SequentialId;
import br.cefetmg.lsi.l2l.creature.bd.ActionSelectionType;
import br.cefetmg.lsi.l2l.creature.conditioning.expectancy.ExpectancyMode;
import br.cefetmg.lsi.l2l.creature.testing.TestingHarness;
import br.cefetmg.lsi.l2l.stimuli.AdenosinergicStimulus;
import br.cefetmg.lsi.l2l.stimuli.AdrenergicStimulus;
import br.cefetmg.lsi.l2l.stimuli.CortisolStimulus;
import br.cefetmg.lsi.l2l.stimuli.EvaluationStimulus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies HomeostaticRegulation's adrenergic handling w.r.t. the circadian sleep-exclusion fix
 * (Finding E) and active-emotion scoping (Finding A).
 */
public class HomeostaticRegulationTest {

    private static LearningSettings circadianOn() {
        return new LearningSettings(true, false,
                List.of(ActionSelectionType.TARGET_DISTANCE, ActionSelectionType.RANDOM));
    }

    private static LearningSettings circadianOff() {
        return new LearningSettings(false, false,
                List.of(ActionSelectionType.TARGET_DISTANCE, ActionSelectionType.RANDOM));
    }

    @Test
    void circadian_on_sleep_not_raised_by_adrenergic() {
        TestingHarness h = TestingHarness.builder().learningSettings(circadianOn()).build();

        double sleepBefore   = h.creature().emotions().getLevel(Constants.SLEEP);
        double hungerBefore  = h.creature().emotions().getLevel(Constants.HUNGER);
        double painBefore    = h.creature().emotions().getLevel(Constants.PAIN);
        double tediumBefore  = h.creature().emotions().getLevel(Constants.TEDIUM);

        SequentialId sid = new SequentialId(999L);
        AdrenergicStimulus adrenergic = new AdrenergicStimulus(sid, sid.next(), Constants.DELTA);
        h.inject(HomeostaticRegulation.class, adrenergic);

        assertEquals(sleepBefore,  h.creature().emotions().getLevel(Constants.SLEEP),  1e-9,
                "SLEEP must not be raised by metabolic drift when circadian is enabled");
        assertTrue(h.creature().emotions().getLevel(Constants.HUNGER) > hungerBefore,
                "HUNGER (a basic drive) must be raised by the metabolic drift");
        assertEquals(painBefore, h.creature().emotions().getLevel(Constants.PAIN), 1e-9,
                "PAIN is an affect driven by nociception, not by metabolic drift");
        assertEquals(tediumBefore, h.creature().emotions().getLevel(Constants.TEDIUM), 1e-9,
                "TEDIUM is an affect regulated by the reward system, not by metabolic drift");
    }

    @Test
    void circadian_off_sleep_raised_by_adrenergic() {
        TestingHarness h = TestingHarness.builder().learningSettings(circadianOff()).build();

        double sleepBefore = h.creature().emotions().getLevel(Constants.SLEEP);

        SequentialId sid = new SequentialId(999L);
        AdrenergicStimulus adrenergic = new AdrenergicStimulus(sid, sid.next(), Constants.DELTA);
        h.inject(HomeostaticRegulation.class, adrenergic);

        assertTrue(h.creature().emotions().getLevel(Constants.SLEEP) > sleepBefore,
                "SLEEP must be raised by metabolic drift when circadian is disabled");
    }

    @Test
    void disabled_emotions_not_raised_by_adrenergic_regardless_of_circadian() {
        for (LearningSettings settings : List.of(circadianOn(), circadianOff())) {
            TestingHarness h = TestingHarness.builder().learningSettings(settings).build();

            SequentialId sid = new SequentialId(999L);
            AdrenergicStimulus adrenergic = new AdrenergicStimulus(sid, sid.next(), Constants.DELTA * 5000);
            h.inject(HomeostaticRegulation.class, adrenergic);

            assertEquals(Constants.MIN_AROUSAL_LEVEL, h.creature().emotions().getLevel(Constants.STRESS),    1e-9);
            assertEquals(Constants.MIN_AROUSAL_LEVEL, h.creature().emotions().getLevel(Constants.APATHY),    1e-9);
            assertEquals(Constants.MIN_AROUSAL_LEVEL, h.creature().emotions().getLevel(Constants.FEAR),      1e-9);
            assertEquals(Constants.MIN_AROUSAL_LEVEL, h.creature().emotions().getLevel(Constants.CURIOSITY), 1e-9);
            assertEquals(Constants.MIN_AROUSAL_LEVEL, h.creature().emotions().getLevel(Constants.FERTILITY), 1e-9);
        }
    }

    private static LearningSettings expectancyOn() {
        return new LearningSettings(
                true, true,
                List.of(ActionSelectionType.TARGET_DISTANCE, ActionSelectionType.RANDOM),
                true, ExpectancyMode.DISCRETE,
                false, false, false, false
        );
    }

    private static LearningSettings endocrineOn() {
        return new LearningSettings(
                true, false,
                List.of(ActionSelectionType.TARGET_DISTANCE, ActionSelectionType.RANDOM),
                false, ExpectancyMode.DISCRETE,
                false,  // neuromodulationEnabled
                false,  // actionTendencyEnabled
                false,  // orexinEnabled
                true    // endocrineEnabled
        );
    }

    @Test
    void deprivation_rpe_not_emitted_when_drive_below_equilibrium_band() {
        TestingHarness h = TestingHarness.builder().learningSettings(expectancyOn()).build();
        // Hunger starts at MIN_AROUSAL_LEVEL (0.18) < EQUILIBRIUM_BAND_UPPER (2.0); no RPE.
        SequentialId sid = new SequentialId(888L);
        for (int i = 0; i < Constants.DEPRIVATION_RPE_INTERVAL; i++) {
            h.inject(HomeostaticRegulation.class, new AdrenergicStimulus(sid, sid.next(), Constants.DELTA));
        }
        assertFalse(h.valuationRecorder().hasAny(EvaluationStimulus.class),
                "No deprivation EvaluationStimulus expected when drive is below EQUILIBRIUM_BAND_UPPER");
    }

    @Test
    void deprivation_rpe_emitted_when_drive_above_equilibrium_band() {
        TestingHarness h = TestingHarness.builder().learningSettings(expectancyOn()).build();
        // Push hunger well above the equilibrium band.
        h.creature().emotions().regulate(Constants.HUNGER, Constants.EQUILIBRIUM_BAND_UPPER + 1.0);
        SequentialId sid = new SequentialId(888L);

        // Cycle counter starts at 0; first tick (cycle=0) satisfies 0 % INTERVAL == 0 → fires.
        h.inject(HomeostaticRegulation.class, new AdrenergicStimulus(sid, sid.next(), 0.0));
        assertTrue(h.valuationRecorder().hasAny(EvaluationStimulus.class),
                "Deprivation EvaluationStimulus must be emitted on cycle 0 when drive > EQUILIBRIUM_BAND_UPPER");

        // The EvaluationStimulus carries positive arousalVariation → reward = -delta < 0 → negative RPE.
        EvaluationStimulus eval = (EvaluationStimulus) h.valuationRecorder().lastOf(EvaluationStimulus.class);
        assertTrue(eval.arousalVariation >= 0,
                "arousalVariation must be non-negative (drive went up) so Valuation emits negative RPE");
    }

    @Test
    void deprivation_rpe_emitted_for_sleep_via_adenosinergic() {
        TestingHarness h = TestingHarness.builder().learningSettings(expectancyOn()).build();
        h.creature().emotions().regulate(Constants.SLEEP, Constants.EQUILIBRIUM_BAND_UPPER + 1.0);
        SequentialId sid = new SequentialId(888L);
        for (int i = 0; i < Constants.DEPRIVATION_RPE_INTERVAL; i++) {
            h.inject(HomeostaticRegulation.class,
                    new AdenosinergicStimulus(sid, sid.next(), Constants.BASE_SLEEP_DRIVE));
        }
        assertTrue(h.valuationRecorder().hasAny(EvaluationStimulus.class),
                "Deprivation EvaluationStimulus must fire for SLEEP drive via AdenosinergicStimulus");
    }

    @Test
    void stressor_streak_gate_fires_only_after_sustain_ticks() {
        // Inject SUSTAIN_TICKS-1 above-threshold adrenergic stimuli — gate must not fire.
        TestingHarness hBelow = TestingHarness.builder().learningSettings(endocrineOn()).build();
        SequentialId sid = new SequentialId(777L);
        for (int i = 0; i < Constants.CORTISOL_STRESSOR_SUSTAIN_TICKS - 1; i++) {
            double high = Constants.STRESS_ACTIVATION_THRESHOLD + 1.0;
            double cur  = hBelow.creature().emotions().getLevel(Constants.HUNGER);
            hBelow.creature().emotions().regulate(Constants.HUNGER, high - cur);
            hBelow.inject(HomeostaticRegulation.class,
                    new AdrenergicStimulus(sid, sid.next(), 0.0));
        }
        assertFalse(hBelow.endocrineRecorder().hasAny(CortisolStimulus.class),
                "CortisolStimulus must NOT be emitted before streak reaches CORTISOL_STRESSOR_SUSTAIN_TICKS");

        // One more injection — streak reaches threshold, gate fires.
        TestingHarness hAt = TestingHarness.builder().learningSettings(endocrineOn()).build();
        for (int i = 0; i < Constants.CORTISOL_STRESSOR_SUSTAIN_TICKS; i++) {
            double high = Constants.STRESS_ACTIVATION_THRESHOLD + 1.0;
            double cur  = hAt.creature().emotions().getLevel(Constants.HUNGER);
            hAt.creature().emotions().regulate(Constants.HUNGER, high - cur);
            hAt.inject(HomeostaticRegulation.class,
                    new AdrenergicStimulus(sid, sid.next(), 0.0));
        }
        assertTrue(hAt.endocrineRecorder().hasAny(CortisolStimulus.class),
                "CortisolStimulus must be emitted once streak reaches CORTISOL_STRESSOR_SUSTAIN_TICKS");
    }
}
