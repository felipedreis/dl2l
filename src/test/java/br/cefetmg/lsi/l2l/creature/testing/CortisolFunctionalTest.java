package br.cefetmg.lsi.l2l.creature.testing;

import br.cefetmg.lsi.l2l.cluster.settings.LearningSettings;
import br.cefetmg.lsi.l2l.common.Constants;
import br.cefetmg.lsi.l2l.creature.bd.ActionSelectionType;
import br.cefetmg.lsi.l2l.creature.conditioning.expectancy.ExpectancyMode;
import br.cefetmg.lsi.l2l.stimuli.CortisolStimulus;
import br.cefetmg.lsi.l2l.stimuli.EndocrineState;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Functional (whole-pipeline) tests for the cortisol/endocrine system through a TestingCreature:
 * (1) circadian morning pulse fires exactly once per circadian period (phase wrap);
 * (2) stressor pathway fires when a drive arousal exceeds STRESS_ACTIVATION_THRESHOLD;
 * (3) no cortisol messages when endocrineEnabled=false.
 */
class CortisolFunctionalTest {

    private static LearningSettings endocrineOn() {
        return new LearningSettings(
                true, false,
                List.of(ActionSelectionType.AFFORDANCE, ActionSelectionType.RANDOM),
                true, ExpectancyMode.DISCRETE,
                true,   // neuromodulationEnabled
                false,  // actionTendencyEnabled — OFF for isolation
                false,  // orexinEnabled
                true    // endocrineEnabled
        );
    }

    private static LearningSettings endocrineOff() {
        return new LearningSettings(
                true, false,
                List.of(ActionSelectionType.AFFORDANCE, ActionSelectionType.RANDOM),
                true, ExpectancyMode.DISCRETE,
                true,   // neuromodulationEnabled
                false,  // actionTendencyEnabled
                false,  // orexinEnabled
                false   // endocrineEnabled — OFF
        );
    }

    // ---------------------------------------------------------------------------------------
    // 1. Circadian morning pulse
    // ---------------------------------------------------------------------------------------

    @Test
    void morning_cortisol_pulse_delivered_after_circadian_wrap() {
        TestingHarness h = TestingHarness.builder().learningSettings(endocrineOn()).build();

        // Run one full circadian period + 1 extra tick to guarantee a wrap occurs.
        for (int i = 0; i < Constants.CIRCADIAN_PERIOD_TICKS + 1; i++) h.tick();

        assertTrue(h.endocrineRecorder().hasAny(CortisolStimulus.class),
                "a CortisolStimulus morning pulse must arrive at EndocrineSystem after one full circadian period");
        assertTrue(h.fullRecorder().hasAny(EndocrineState.class),
                "EndocrineSystem must publish EndocrineState to FullAppraisal after processing the morning pulse");
    }

    @Test
    void no_cortisol_before_first_circadian_wrap() {
        TestingHarness h = TestingHarness.builder().learningSettings(endocrineOn()).build();

        // Run only half a period — phase has not wrapped yet.
        for (int i = 0; i < Constants.CIRCADIAN_PERIOD_TICKS / 2; i++) h.tick();

        assertFalse(h.endocrineRecorder().hasAny(CortisolStimulus.class),
                "no CortisolStimulus should arrive before the first circadian phase wrap");
    }

    // ---------------------------------------------------------------------------------------
    // 2. Stressor pathway
    // ---------------------------------------------------------------------------------------

    @Test
    void stressor_cortisol_emitted_when_hunger_exceeds_threshold() {
        TestingHarness h = TestingHarness.builder().learningSettings(endocrineOn()).build();

        // Drive hunger above the stress activation threshold and run one tick so
        // HomeostaticRegulation processes the adrenergic drift and calls emitCortisolIfStressed.
        double targetHunger = Constants.STRESS_ACTIVATION_THRESHOLD + 1.0;
        double currentHunger = h.creature().emotions().getLevel(Constants.HUNGER);
        h.creature().emotions().regulate(Constants.HUNGER, targetHunger - currentHunger);

        h.tick();

        assertTrue(h.endocrineRecorder().hasAny(CortisolStimulus.class),
                "HomeostaticRegulation must emit CortisolStimulus when hunger exceeds STRESS_ACTIVATION_THRESHOLD");
    }

    // ---------------------------------------------------------------------------------------
    // 3. Disabled
    // ---------------------------------------------------------------------------------------

    @Test
    void no_cortisol_messages_when_endocrine_disabled() {
        TestingHarness h = TestingHarness.builder().learningSettings(endocrineOff()).build();

        for (int i = 0; i < Constants.CIRCADIAN_PERIOD_TICKS + 5; i++) h.tick();

        assertFalse(h.endocrineRecorder().hasAny(CortisolStimulus.class),
                "no CortisolStimulus should be emitted when endocrineEnabled=false");
    }
}
