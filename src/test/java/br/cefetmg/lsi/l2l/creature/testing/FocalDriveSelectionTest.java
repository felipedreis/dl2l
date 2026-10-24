package br.cefetmg.lsi.l2l.creature.testing;

import br.cefetmg.lsi.l2l.cluster.settings.LearningSettings;
import br.cefetmg.lsi.l2l.common.Constants;
import br.cefetmg.lsi.l2l.creature.bd.ActionSelectionType;
import br.cefetmg.lsi.l2l.creature.bd.ChosenActionState;
import br.cefetmg.lsi.l2l.creature.common.ActionType;
import br.cefetmg.lsi.l2l.creature.conditioning.expectancy.ExpectancyMode;
import br.cefetmg.lsi.l2l.stimuli.CorticalStimulus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Issue #99: with {@code focalDriveEnabled} the tendency filter regulates the FocusTracker's
 * focal drive, not the per-cycle most-aroused one, and the focal drive is persisted per decision.
 * With no perception the candidates are [SLEEP, WANDER, OBSERVE]; HUNGER's tendency leaves only
 * WANDER, SLEEP's leaves {SLEEP, WANDER}, so the outcome with HUNGER focal is exact.
 */
class FocalDriveSelectionTest {

    private static LearningSettings settings(boolean focalDrive) {
        return new LearningSettings(true, false,
                List.of(ActionSelectionType.AFFORDANCE, ActionSelectionType.RANDOM),
                false, ExpectancyMode.DISCRETE, false, true, false, false, true, focalDrive);
    }

    private static String lastFocalDrive(TestingHarness h) {
        List<ChosenActionState> chosen = h.bdSink().ofType(ChosenActionState.class);
        return chosen.get(chosen.size() - 1).getFocalDrive();
    }

    /** HUNGER opens an episode, then SLEEP overtakes it by less than delta (0.3). */
    private static TestingHarness hungerFocalSleepSlightlyHigher(boolean focalDrive) {
        TestingHarness h = TestingHarness.builder().learningSettings(settings(focalDrive)).build();
        h.creature().emotions().regulate(Constants.HUNGER, 3.0);
        h.tick(); // HUNGER well above theta_on: episode opens
        double hunger = h.creature().emotions().getLevel(Constants.HUNGER);
        h.creature().emotions().regulate(Constants.SLEEP, hunger + 0.1 - Constants.MIN_AROUSAL_LEVEL);
        return h;
    }

    @Test
    void focalDriveOn_filterRegulatesFocalDriveNotTheMostAroused() {
        TestingHarness h = hungerFocalSleepSlightlyHigher(true);
        assertTrue(h.creature().emotions().getLevel(Constants.SLEEP)
                > h.creature().emotions().getLevel(Constants.HUNGER), "SLEEP is the most aroused");

        for (int i = 0; i < 5; i++) {
            h.tick();
            CorticalStimulus c = h.effectorCortexRecorder().lastOf(CorticalStimulus.class);
            assertEquals(ActionType.WANDER, c.action, "HUNGER stays focal within delta: tendency is {WANDER}");
            assertEquals(Constants.HUNGER, lastFocalDrive(h));
        }
    }

    @Test
    void focalDriveOff_persistsNoFocalDriveAndFollowsTheMostAroused() {
        TestingHarness h = hungerFocalSleepSlightlyHigher(false);
        boolean sleptOrNot = false;
        for (int i = 0; i < 30 && !sleptOrNot; i++) {
            h.tick();
            sleptOrNot = h.effectorCortexRecorder().lastOf(CorticalStimulus.class).action == ActionType.SLEEP;
            assertNull(lastFocalDrive(h));
        }
        assertTrue(sleptOrNot, "SLEEP's tendency {SLEEP, WANDER} lets SLEEP through when it is the most aroused");
    }

    @Test
    void noEpisodeOpen_fallsBackToMostArousedAndPersistsNull() {
        TestingHarness h = TestingHarness.builder().learningSettings(settings(true)).build();
        h.tick(); // all drives at baseline: below theta_on

        assertNull(lastFocalDrive(h));
    }

    private static List<ActionType> calmActions(boolean focalDrive) {
        TestingHarness h = TestingHarness.builder().learningSettings(settings(focalDrive)).seed(4242L).build();
        List<ActionType> actions = new java.util.ArrayList<>();
        for (int i = 0; i < 40; i++) {
            h.tick();
            actions.add(h.effectorCortexRecorder().lastOf(CorticalStimulus.class).action);
        }
        return actions;
    }

    @Test
    void calmCreature_choosesTheSameActionsWithTheFlagOnAndOff() {
        assertEquals(calmActions(false), calmActions(true),
                "no episode open => the filters get the most-aroused emotion, exactly as with the flag off");
    }
}
