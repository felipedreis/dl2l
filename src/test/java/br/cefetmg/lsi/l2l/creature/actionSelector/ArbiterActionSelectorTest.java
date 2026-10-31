package br.cefetmg.lsi.l2l.creature.actionSelector;

import br.cefetmg.lsi.l2l.common.Constants;
import br.cefetmg.lsi.l2l.common.SequentialId;
import br.cefetmg.lsi.l2l.creature.arbiter.Arbiter;
import br.cefetmg.lsi.l2l.creature.arbiter.ArbitrationDecision;
import br.cefetmg.lsi.l2l.creature.arbiter.Estimate;
import br.cefetmg.lsi.l2l.creature.arbiter.OperantEstimator;
import br.cefetmg.lsi.l2l.creature.bd.ActionSelectionType;
import br.cefetmg.lsi.l2l.creature.common.Action;
import br.cefetmg.lsi.l2l.creature.common.ActionType;
import br.cefetmg.lsi.l2l.creature.common.Perception;
import br.cefetmg.lsi.l2l.creature.components.Emotion;
import br.cefetmg.lsi.l2l.world.FruitType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Issue #104: the arbiter as an {@link ActionSelector}, with the real pre-filters and real
 * {@link OperantEstimator}s. Covers the selection contract and the focal-drive key of the estimates.
 */
class ArbiterActionSelectorTest {

    private static final double[] H = {0.0, 0.0, 0.0, 0.0};
    private static final Emotion HUNGER = new Emotion(Constants.HUNGER);

    private static Action on(ActionType type, FruitType fruit, double distance, int id) {
        return new Action(type, new Perception(fruit, new SequentialId(id), distance, 0));
    }

    private static Action noObject(ActionType type, int id) {
        return new Action(type, new Perception(null, new SequentialId(id), 10, 0));
    }

    /** Real estimator with one observation per (drive, action): each value is recorded under its drive. */
    private static OperantEstimator estimatorWith(String drive, Action action, double... values) {
        OperantEstimator e = new OperantEstimator(new double[] {50, 100}, 1.0);
        for (double v : values) {
            e.record(drive, action, v);
        }
        return e;
    }

    private static ArbiterActionSelector selector(List<ActionFilter> pre, OperantEstimator e, long seed) {
        return new ArbiterActionSelector(pre, new Arbiter(
                List.of(new Arbiter.Controller(Arbiter.ControllerType.OPERANT, e)),
                Arbiter.Params.defaults(), new Random(seed)));
    }

    private static SelectionContext ctx(String focalDrive) {
        return new SelectionContext(HUNGER, H, focalDrive, 1.0);
    }

    // ---- 9b. Pre-filters, single candidate, decision bookkeeping -----------------------------

    @Test
    void theTargetDistancePreFilterRunsFirstAndTheArbiterChoosesAmongWhatItLeaves() {
        // Two instances of the same (target, action): TargetDistanceFilter keeps only the nearest.
        Action near = on(ActionType.EAT, FruitType.RED_APPLE, 10, 1);
        Action far = on(ActionType.EAT, FruitType.RED_APPLE, 30, 2);
        ArbiterActionSelector s = selector(List.of(new TargetDistanceFilter()),
                new OperantEstimator(new double[] {50, 100}, 1.0), 1);

        Action chosen = s.selectOne(List.of(far, near), ctx(null));

        assertEquals(near, chosen);
        ArbitrationDecision d = s.takeLastDecision().orElseThrow();
        assertEquals(1, d.candidateCount(), "the pre-filter left one candidate");
        assertEquals(ArbitrationDecision.ExplorationSource.SINGLE, d.source());
    }

    @Test
    void theTendencyPriorNarrowsBeforeTheArbiter() {
        // Hunger's tendency is {EAT, APPROACH, WANDER}: SLEEP and AVOID are removed, leaving EAT.
        Action eat = noObject(ActionType.EAT, 1);
        List<ActionFilter> pre = List.of(new ActionTendencyFilter(
                br.cefetmg.lsi.l2l.cluster.settings.LearningSettings.DEFAULT_ACTION_TENDENCIES));
        ArbiterActionSelector s = selector(pre, new OperantEstimator(new double[] {50, 100}, 1.0), 1);

        Action chosen = s.selectOne(List.of(noObject(ActionType.SLEEP, 2), eat, noObject(ActionType.AVOID, 3)),
                ctx(null));

        assertEquals(eat, chosen);
        assertEquals(1, s.takeLastDecision().orElseThrow().candidateCount());
    }

    @Test
    void aSingleCandidateStillGoesThroughTheArbiterAndItsQIsKnown() {
        OperantEstimator e = estimatorWith(Constants.HUNGER, noObject(ActionType.EAT, 1), 1.0);
        ArbiterActionSelector s = selector(List.of(), e, 1);
        Action only = noObject(ActionType.EAT, 1);

        Action chosen = s.selectOne(List.of(only), ctx(Constants.HUNGER));

        assertEquals(only, chosen);
        ArbitrationDecision d = s.takeLastDecision().orElseThrow();
        assertEquals(ArbitrationDecision.ExplorationSource.SINGLE, d.source());
        assertEquals(e.estimate(List.of(only), H, Constants.HUNGER).get(0), d.chosenEstimate(),
                "the arbiter estimated the lone candidate");
    }

    @Test
    void takeLastDecisionReturnsTheDecisionOnceThenEmpty() {
        ArbiterActionSelector s = selector(List.of(), new OperantEstimator(new double[] {50, 100}, 1.0), 1);
        s.selectOne(List.of(noObject(ActionType.EAT, 1), noObject(ActionType.AVOID, 2)), ctx(null));

        Optional<ArbitrationDecision> first = s.takeLastDecision();
        assertTrue(first.isPresent());
        assertFalse(s.takeLastDecision().isPresent(), "the second take is empty");
    }

    @Test
    void theFocalDriveInTheContextIsTheCellUsed() {
        Action eat = noObject(ActionType.EAT, 1);
        Action avoid = noObject(ActionType.AVOID, 2);
        // EAT under hunger only: twenty observations of 2.0.
        OperantEstimator e = new OperantEstimator(new double[] {50, 100}, 1.0);
        for (int i = 0; i < 20; i++) {
            e.record(Constants.HUNGER, eat, 2.0);
        }
        ArbiterActionSelector s = selector(List.of(), e, 1);
        List<Action> both = List.of(eat, avoid);

        s.selectOne(both, ctx(Constants.HUNGER));
        ArbitrationDecision hungry = s.takeLastDecision().orElseThrow();
        assertTrue(hungry.consultations().get(0).conf() > 0.9, "under hunger EAT is decisive");
        assertEquals(eat, hungry.candidates().get(0));
        assertTrue(hungry.estimates().get(0).q() > 1.0, "the EAT cell under hunger has its trained value");

        s.selectOne(both, ctx(Constants.SLEEP));
        ArbitrationDecision sleepy = s.takeLastDecision().orElseThrow();
        assertEquals(0.5, sleepy.consultations().get(0).conf(), 0.0, "under sleep both cells are unseen: a tie");
    }

    @Test
    void lastSelectionTypeIsArbiter() {
        ArbiterActionSelector s = selector(List.of(), new OperantEstimator(new double[] {50, 100}, 1.0), 1);
        assertEquals(ActionSelectionType.ARBITER, s.lastSelectionType());
    }

    // ---- 10. valueOf ------------------------------------------------------------------------

    @Test
    void valueOfIsTheCheapestControllersEstimateForThatAction() {
        Action eat = noObject(ActionType.EAT, 1);
        OperantEstimator e = estimatorWith(Constants.HUNGER, eat, 0.5, 1.5);
        ArbiterActionSelector s = selector(List.of(), e, 1);

        Estimate v = s.valueOf(eat, ctx(Constants.HUNGER));

        assertEquals(e.estimate(List.of(eat), H, Constants.HUNGER).get(0), v);
    }

    @Test
    void valueOfDoesNotDisturbTheLastDecisionOrItsRandomStream() {
        Action eat = noObject(ActionType.EAT, 1);
        Action avoid = noObject(ActionType.AVOID, 2);
        List<Action> both = List.of(eat, avoid);
        // Two selectors over identical estimators and seeds: one asks valueOf between its two selections.
        ArbiterActionSelector asked = selector(List.of(), new OperantEstimator(new double[] {50, 100}, 1.0), 5);
        ArbiterActionSelector quiet = selector(List.of(), new OperantEstimator(new double[] {50, 100}, 1.0), 5);

        Action firstAsked = asked.selectOne(both, ctx(null));
        ArbitrationDecision lastAsked = asked.takeLastDecision().orElseThrow();
        asked.valueOf(eat, ctx(null));
        Action secondAsked = asked.selectOne(both, ctx(null));

        Action firstQuiet = quiet.selectOne(both, ctx(null));
        quiet.takeLastDecision();
        Action secondQuiet = quiet.selectOne(both, ctx(null));

        assertEquals(firstQuiet, firstAsked);
        assertEquals(firstQuiet, lastAsked.chosen(), "the decision holds what was chosen");
        assertEquals(secondQuiet, secondAsked, "valueOf draws no random number");
    }
}
