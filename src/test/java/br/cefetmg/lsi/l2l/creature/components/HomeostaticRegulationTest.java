package br.cefetmg.lsi.l2l.creature.components;

import br.cefetmg.lsi.l2l.cluster.settings.LearningSettings;
import br.cefetmg.lsi.l2l.common.Constants;
import br.cefetmg.lsi.l2l.common.SequentialId;
import br.cefetmg.lsi.l2l.creature.bd.ActionSelectionType;
import br.cefetmg.lsi.l2l.creature.testing.TestingHarness;
import br.cefetmg.lsi.l2l.stimuli.AdrenergicStimulus;
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
                "HUNGER must be raised by adrenergic even with circadian on");
        assertTrue(h.creature().emotions().getLevel(Constants.PAIN)   > painBefore,
                "PAIN must be raised by adrenergic even with circadian on");
        assertTrue(h.creature().emotions().getLevel(Constants.TEDIUM) > tediumBefore,
                "TEDIUM must be raised by adrenergic even with circadian on");
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
}
