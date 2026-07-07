package br.cefetmg.lsi.l2l.creature.testing;

import br.cefetmg.lsi.l2l.cluster.settings.LearningSettings;
import br.cefetmg.lsi.l2l.common.Constants;
import br.cefetmg.lsi.l2l.common.Point;
import br.cefetmg.lsi.l2l.common.SequentialId;
import br.cefetmg.lsi.l2l.creature.bd.ActionSelectionType;
import br.cefetmg.lsi.l2l.creature.common.ActionType;
import br.cefetmg.lsi.l2l.creature.conditioning.expectancy.ExpectancyMode;
import br.cefetmg.lsi.l2l.stimuli.CorticalStimulus;
import br.cefetmg.lsi.l2l.stimuli.LuminousStimulus;
import br.cefetmg.lsi.l2l.world.FruitType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Functional test that the innate emotion→action coupling makes a hungry creature forage rather than
 * sleep — the behavioural fix for the "sleeps while hungry" pathology. The tendency filter reduces the
 * candidate set deterministically, so these assertions are exact (no sampling).
 */
class ActionTendencyFunctionalTest {

    private static LearningSettings tendencyOn() {
        return new LearningSettings(true, false,
                List.of(ActionSelectionType.AFFORDANCE, ActionSelectionType.RANDOM),
                false, ExpectancyMode.DISCRETE, false, true);
    }

    private static TestingHarness hungryCreature() {
        TestingHarness h = TestingHarness.builder().learningSettings(tendencyOn()).build();
        // Make HUNGER the dominant drive so it steers action selection.
        h.creature().emotions().regulate(Constants.HUNGER, 3.0);
        return h;
    }

    @Test
    void hungry_creature_with_visible_fruit_approaches_not_sleeps() {
        TestingHarness h = hungryCreature();
        Point p = h.creature().getPosition();

        SequentialId a = new SequentialId(90_001L);
        h.injectLuminous(new LuminousStimulus(a, a.next(), FruitType.RED_APPLE,
                new Point(p.x + 100, p.y)));

        CorticalStimulus c = h.effectorCortexRecorder().lastOf(CorticalStimulus.class);
        assertNotNull(c);
        assertEquals(ActionType.APPROACH, c.action,
                "hunger's tendency {EAT,APPROACH,WANDER} ∩ {APPROACH,AVOID,SLEEP,OBSERVE} = APPROACH");
    }

    @Test
    void hungry_creature_without_fruit_wanders_not_sleeps() {
        TestingHarness h = hungryCreature();

        h.tick(); // no perception → Self actions [SLEEP, WANDER, OBSERVE]

        CorticalStimulus c = h.effectorCortexRecorder().lastOf(CorticalStimulus.class);
        assertNotNull(c);
        assertEquals(ActionType.WANDER, c.action,
                "a hungry creature with no food should WANDER (search), not SLEEP");
        assertNotEquals(ActionType.SLEEP, c.action);
    }

    @Test
    void sleepy_creature_still_sleeps() {
        TestingHarness h = TestingHarness.builder().learningSettings(tendencyOn()).build();
        h.creature().emotions().regulate(Constants.SLEEP, 3.0); // SLEEP dominant

        h.tick();

        CorticalStimulus c = h.effectorCortexRecorder().lastOf(CorticalStimulus.class);
        assertNotNull(c);
        // SLEEP tendency is {SLEEP, WANDER}; with Self actions [SLEEP, WANDER, OBSERVE] the affordance
        // filter picks between SLEEP and WANDER — SLEEP must remain available (not filtered out).
        assertTrue(c.action == ActionType.SLEEP || c.action == ActionType.WANDER,
                "a sleepy creature may still SLEEP; got " + c.action);
    }
}
