package br.cefetmg.lsi.l2l.creature.testing;

import br.cefetmg.lsi.l2l.cluster.settings.LearningSettings;
import br.cefetmg.lsi.l2l.common.Constants;
import br.cefetmg.lsi.l2l.creature.bd.ActionSelectionType;
import br.cefetmg.lsi.l2l.creature.conditioning.expectancy.ExpectancyMode;
import br.cefetmg.lsi.l2l.stimuli.TediumStimulus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Issue #85 follow-up: neither Mapa (2009) nor Campos (2015) - the architectures p84
 * validates parity against - modelled tedium/boredom. Default-off so the base architecture
 * matches what is being validated, and so a creature's arousal
 * (EmotionalSystemActor.getMaxArousal()) is never pinned by an affect neither reference
 * model has an opinion about.
 *
 * <p>Regression coverage for the gate itself, at the level FullAppraisal actually dispatches
 * from - LearningSettingsTest/NeuromodulatorSystemTest cover the flag and the neuromodulator
 * path respectively, this covers FullAppraisal.dispatchTediumStimulus (the legacy path).
 */
class TediumGatingTest {

    private static LearningSettings withTedium(boolean tediumEnabled) {
        return new LearningSettings(true, false,
                List.of(ActionSelectionType.TARGET_DISTANCE, ActionSelectionType.RANDOM),
                false, ExpectancyMode.DISCRETE,
                false,          // neuromodulationEnabled — legacy dispatchTediumStimulus path
                false, false, false, true, tediumEnabled);
    }

    @Test
    void no_tedium_stimulus_is_ever_dispatched_by_default() {
        TestingHarness h = TestingHarness.builder().learningSettings(withTedium(false)).build();

        for (int i = 0; i < 100; i++) h.tick();

        assertFalse(h.homeostaticRecorder().hasAny(TediumStimulus.class),
                "tediumEnabled defaults false — dispatchTediumStimulus must be a full no-op, "
                        + "regardless of which action gets selected over 100 ticks");
        assertEquals(Constants.MIN_AROUSAL_LEVEL, h.creature().emotions().getLevel(Constants.TEDIUM),
                1e-9, "tedium must never leave its floor when disabled");
    }

    @Test
    void tedium_stimulus_dispatches_when_explicitly_enabled() {
        TestingHarness h = TestingHarness.builder().learningSettings(withTedium(true)).build();

        for (int i = 0; i < 100; i++) h.tick();

        assertTrue(h.homeostaticRecorder().hasAny(TediumStimulus.class),
                "with tediumEnabled=true the legacy path must still dispatch, as before this change");
    }
}
