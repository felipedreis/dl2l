package br.cefetmg.lsi.l2l.creature.components;

import br.cefetmg.lsi.l2l.cluster.settings.LearningSettings;
import br.cefetmg.lsi.l2l.common.Constants;
import br.cefetmg.lsi.l2l.common.SequentialId;
import br.cefetmg.lsi.l2l.creature.bd.ActionSelectionType;
import br.cefetmg.lsi.l2l.creature.common.ActionType;
import br.cefetmg.lsi.l2l.creature.components.Emotion;
import br.cefetmg.lsi.l2l.creature.conditioning.expectancy.ExpectancyContext;
import br.cefetmg.lsi.l2l.creature.conditioning.expectancy.ExpectancyMode;
import br.cefetmg.lsi.l2l.creature.testing.TestingHarness;
import br.cefetmg.lsi.l2l.stimuli.DopaminergicStimulus;
import br.cefetmg.lsi.l2l.stimuli.EvaluationStimulus;
import br.cefetmg.lsi.l2l.world.FruitType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies Valuation's reward-prediction-error path: a phasic DopaminergicStimulus is emitted with
 * the correct RPE, the expectancy prediction learns so the RPE shrinks with repetition, and the
 * legacy path is untouched when expectancy is disabled.
 */
public class ValuationRpeTest {

    private static final SequentialId SID = new SequentialId(999L);

    private static LearningSettings expectancyOn(ExpectancyMode mode) {
        return new LearningSettings(true, false,
                List.of(ActionSelectionType.TARGET_DISTANCE, ActionSelectionType.AFFORDANCE, ActionSelectionType.RANDOM),
                true, mode, false);
    }

    private static LearningSettings expectancyOff() {
        return new LearningSettings(true, false,
                List.of(ActionSelectionType.TARGET_DISTANCE, ActionSelectionType.AFFORDANCE, ActionSelectionType.RANDOM));
    }

    private static EvaluationStimulus eatApple(double arousalVariation, double hungerLevel) {
        Emotion hunger = new Emotion(Constants.HUNGER);
        hunger.setLevel(hungerLevel);
        return new EvaluationStimulus(SID, SID.next(), SID, FruitType.RED_APPLE, ActionType.EAT,
                hunger, arousalVariation, new ExpectancyContext(Constants.HUNGER, hungerLevel));
    }

    @Test
    void first_reward_yields_rpe_equal_to_reward_and_releases_dopamine() {
        TestingHarness h = TestingHarness.builder().learningSettings(expectancyOn(ExpectancyMode.DISCRETE)).build();

        // arousalVariation = -2.0 → reward = +2.0; expected starts at neutral prior 0 → rpe = 2.0.
        h.inject(Valuation.class, eatApple(-2.0, 6.0));

        DopaminergicStimulus da = h.neuromodulatorRecorder().lastOf(DopaminergicStimulus.class);
        assertNotNull(da, "Valuation must emit a phasic DopaminergicStimulus");
        assertEquals(2.0, da.rpe, 1e-9);
        assertEquals(ActionType.EAT, da.action);
    }

    @Test
    void repeated_identical_outcome_shrinks_the_prediction_error() {
        TestingHarness h = TestingHarness.builder().learningSettings(expectancyOn(ExpectancyMode.DISCRETE)).build();

        for (int i = 0; i < 60; i++) {
            h.inject(Valuation.class, eatApple(-2.0, 6.0));
        }

        // Expectation has converged to the reward, so the latest RPE is ~0.
        DopaminergicStimulus da = h.neuromodulatorRecorder().lastOf(DopaminergicStimulus.class);
        assertNotNull(da);
        assertEquals(0.0, da.rpe, 1e-3);
    }

    @Test
    void disabled_expectancy_emits_no_dopamine() {
        TestingHarness h = TestingHarness.builder().learningSettings(expectancyOff()).build();

        h.inject(Valuation.class, eatApple(-2.0, 6.0));

        assertFalse(h.neuromodulatorRecorder().hasAny(DopaminergicStimulus.class),
                "legacy valuation path must not release dopamine");
    }
}
