package br.cefetmg.lsi.l2l.creature.testing;

import br.cefetmg.lsi.l2l.cluster.settings.LearningSettings;
import br.cefetmg.lsi.l2l.common.Constants;
import br.cefetmg.lsi.l2l.creature.bd.ActionSelectionType;
import br.cefetmg.lsi.l2l.creature.conditioning.expectancy.ExpectancyMode;
import br.cefetmg.lsi.l2l.stimuli.CortisolStimulus;
import br.cefetmg.lsi.l2l.stimuli.EndocrineTick;
import br.cefetmg.lsi.l2l.stimuli.EndocrineState;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Functional (whole-pipeline) tests for the cortisol/endocrine system through a TestingCreature:
 * (1) EndocrineTick is delivered to EndocrineSystem each cognitive cycle;
 * (2) stressor pathway fires only after CORTISOL_STRESSOR_SUSTAIN_TICKS consecutive ticks above threshold;
 * (3) transient stress (fewer than SUSTAIN_TICKS ticks) does not trigger CortisolStimulus;
 * (4) no endocrine messages when endocrineEnabled=false.
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
    // 1. EndocrineTick pacemaker
    // ---------------------------------------------------------------------------------------

    @Test
    void endocrine_tick_delivered_each_cycle() {
        TestingHarness h = TestingHarness.builder().learningSettings(endocrineOn()).build();

        h.tick();

        assertTrue(h.endocrineRecorder().hasAny(EndocrineTick.class),
                "PartialAppraisal must deliver an EndocrineTick to EndocrineSystem each cognitive cycle");
        assertTrue(h.fullRecorder().hasAny(EndocrineState.class),
                "EndocrineSystem must publish EndocrineState to FullAppraisal after processing the tick");
    }

    // ---------------------------------------------------------------------------------------
    // 2. Stressor sustained-deprivation gate
    // ---------------------------------------------------------------------------------------

    @Test
    void stressor_cortisol_emitted_after_sustain_ticks() {
        TestingHarness h = TestingHarness.builder().learningSettings(endocrineOn()).build();

        // Drive hunger above the stress activation threshold.
        double targetHunger = Constants.STRESS_ACTIVATION_THRESHOLD + 1.0;
        double currentHunger = h.creature().emotions().getLevel(Constants.HUNGER);
        h.creature().emotions().regulate(Constants.HUNGER, targetHunger - currentHunger);

        // Run exactly CORTISOL_STRESSOR_SUSTAIN_TICKS — streak reaches the gate on the last tick.
        for (int i = 0; i < Constants.CORTISOL_STRESSOR_SUSTAIN_TICKS; i++) h.tick();

        assertTrue(h.endocrineRecorder().hasAny(CortisolStimulus.class),
                "HomeostaticRegulation must emit CortisolStimulus after "
                + Constants.CORTISOL_STRESSOR_SUSTAIN_TICKS + " consecutive above-threshold ticks");
    }

    @Test
    void stressor_cortisol_not_emitted_before_sustain_ticks() {
        TestingHarness h = TestingHarness.builder().learningSettings(endocrineOn()).build();

        double targetHunger = Constants.STRESS_ACTIVATION_THRESHOLD + 1.0;
        double currentHunger = h.creature().emotions().getLevel(Constants.HUNGER);
        h.creature().emotions().regulate(Constants.HUNGER, targetHunger - currentHunger);

        // Run one fewer tick than required — streak has not yet reached the gate.
        for (int i = 0; i < Constants.CORTISOL_STRESSOR_SUSTAIN_TICKS - 1; i++) h.tick();

        assertFalse(h.endocrineRecorder().hasAny(CortisolStimulus.class),
                "HomeostaticRegulation must NOT emit CortisolStimulus before the sustain gate fires");
    }

    // ---------------------------------------------------------------------------------------
    // 3. Disabled
    // ---------------------------------------------------------------------------------------

    @Test
    void no_endocrine_messages_when_disabled() {
        TestingHarness h = TestingHarness.builder().learningSettings(endocrineOff()).build();

        for (int i = 0; i < Constants.CIRCADIAN_PERIOD_TICKS + 5; i++) h.tick();

        assertFalse(h.endocrineRecorder().hasAny(EndocrineTick.class),
                "no EndocrineTick should be sent when endocrineEnabled=false");
        assertFalse(h.endocrineRecorder().hasAny(CortisolStimulus.class),
                "no CortisolStimulus should be emitted when endocrineEnabled=false");
    }
}
