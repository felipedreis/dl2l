package br.cefetmg.lsi.l2l.creature.testing;

import br.cefetmg.lsi.l2l.cluster.settings.LearningSettings;
import br.cefetmg.lsi.l2l.common.Constants;
import br.cefetmg.lsi.l2l.common.SequentialId;
import br.cefetmg.lsi.l2l.creature.arbiter.DecisionRecord;
import br.cefetmg.lsi.l2l.creature.arbiter.DriveOutcomeMessage;
import br.cefetmg.lsi.l2l.creature.arbiter.Estimate;
import br.cefetmg.lsi.l2l.creature.arbiter.FocusEvent;
import br.cefetmg.lsi.l2l.creature.arbiter.FocusEvents;
import br.cefetmg.lsi.l2l.creature.arbiter.OperantStore;
import br.cefetmg.lsi.l2l.creature.bd.EpisodeReturnState;
import br.cefetmg.lsi.l2l.creature.common.Action;
import br.cefetmg.lsi.l2l.creature.common.ActionType;
import br.cefetmg.lsi.l2l.creature.common.Perception;
import br.cefetmg.lsi.l2l.creature.conditioning.expectancy.ExpectancyMode;
import br.cefetmg.lsi.l2l.world.FruitType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Issue #110, component level: {@code Valuation} receives the decision, focus and outcome messages of
 * one episode and writes its closed steps to the operant store.
 *
 * <p>The decision cycle is held at 2 while the messages are drained, so each outcome is stamped 2 on
 * arrival in every ordering. Only the queue order differs: the outcome is delivered after the close, or
 * after the decision that follows the close. The buffer's settle rule must then give the same written
 * values, and the same operant estimates, as in order.
 *
 * <p>Expected values, with the default {@code HomeostaticValue} (set point 0.18, unit weights) and
 * {@code gamma = 2^(-1/5)} = {@code g}: hunger {@code h = 2}, so {@code d(h) = 1.82}. An EAT at cycle 2 with
 * outcome {@code K = -1} at cycle 2 has {@code G = -1} and value {@code 1.82 - 0.82 = 1.0}, giving
 * {@code Q = 1.0 / (1 + 1) = 0.5} in the EAT cell. An APPROACH at cycle 1 has {@code G = -g} and value
 * {@code g}, giving {@code Q = g / 2} in the APPROACH cell.
 */
class ValuationEpisodeTest {

    private static final String HUNGER = Constants.HUNGER;
    private static final double[] HUNGRY = {2, 0, 0, 0};
    private static final double[] RELIEF = {-1, 0, 0, 0};
    private static final double GAMMA = Math.pow(2, -0.2);
    private static final double TOL = 1e-9;

    private static final Action APPROACH = action(ActionType.APPROACH, FruitType.GREEN_APPLE, 1);
    private static final Action EAT = action(ActionType.EAT, FruitType.GREEN_APPLE, 2);
    private static final Action WANDER = action(ActionType.WANDER, null, 9);

    private static Action action(ActionType type, FruitType object, int id) {
        return new Action(type, new Perception(object, new SequentialId(id), 10, 0));
    }

    private static LearningSettings settings() {
        return new LearningSettings(false, false, List.of(), false, ExpectancyMode.DISCRETE,
                false, false, false, false, true, true, true);
    }

    private static List<Object> inOrder() {
        return List.of(
                new FocusEvents(1, List.of(new FocusEvent.Opened(1, HUNGER))),
                new DecisionRecord(1, APPROACH, HUNGRY, HUNGER, Double.NaN),
                new DecisionRecord(2, EAT, HUNGRY, HUNGER, Double.NaN),
                new DriveOutcomeMessage(RELIEF),
                new FocusEvents(3, List.of(new FocusEvent.Closed(3, HUNGER, FocusEvent.Outcome.REGULATED))),
                new DecisionRecord(3, WANDER, HUNGRY, null, Double.NaN),
                new DecisionRecord(4, WANDER, HUNGRY, null, Double.NaN));
    }

    /** The outcome overtakes nothing but arrives after the close event. */
    private static List<Object> outcomeAfterClose() {
        List<Object> m = new ArrayList<>(inOrder());
        Object k = m.remove(3);
        m.add(4, k);
        return m;
    }

    /** The outcome arrives after the decision that carries the close, but before the settling one. */
    private static List<Object> outcomeAfterCloseDecision() {
        List<Object> m = new ArrayList<>(inOrder());
        Object k = m.remove(3);
        m.add(5, k);
        return m;
    }

    /** Runs {@code messages} through a fresh creature's Valuation, with the decision cycle held at 2. */
    private static TestingHarness drained(List<Object> messages) {
        TestingHarness h = TestingHarness.builder().learningSettings(settings()).build();
        h.creature().memory().tickDecisionCycle();
        h.creature().memory().tickDecisionCycle();
        for (Object m : messages) {
            h.creature().valuation().tell(m);
        }
        h.processUntilQuiescent();
        return h;
    }

    private static List<Estimate> estimates(TestingHarness h) {
        h.creature().setFocalDriveForTesting(HUNGER);
        OperantStore store = h.creature().operantStore();
        return store.estimate(List.of(EAT, APPROACH), HUNGRY);
    }

    @Test
    void outOfOrderDeliveryWritesTheSameEstimatesAsInOrder() {
        List<Estimate> ordered = estimates(drained(inOrder()));
        List<Estimate> afterClose = estimates(drained(outcomeAfterClose()));
        List<Estimate> afterClosingDecision = estimates(drained(outcomeAfterCloseDecision()));

        assertEquals(ordered, afterClose);
        assertEquals(ordered, afterClosingDecision);
    }

    @Test
    void closedEpisodeMovesTheRecordedCellsByTheHandComputedValues() {
        TestingHarness h = TestingHarness.builder().learningSettings(settings()).build();
        h.creature().setFocalDriveForTesting(HUNGER);
        Estimate eatBefore = h.creature().operantStore().estimate(List.of(EAT), HUNGRY).get(0);
        assertEquals(new Estimate(0, 1, 1), eatBefore, "an unseen cell has the prior");

        h.creature().memory().tickDecisionCycle();
        h.creature().memory().tickDecisionCycle();
        for (Object m : inOrder()) {
            h.creature().valuation().tell(m);
        }
        h.processUntilQuiescent();

        List<Estimate> after = h.creature().operantStore().estimate(List.of(EAT, APPROACH), HUNGRY);
        assertEquals(0.5, after.get(0).q(), TOL, "EAT: value 1.0 over one observation");
        assertEquals(GAMMA / 2, after.get(1).q(), TOL, "APPROACH: value gamma over one observation");
        assertEquals(1 / Math.sqrt(2), after.get(0).se(), TOL);

        // Persistence sink: one row per written step, in decision order.
        List<EpisodeReturnState> rows = h.bdSink().ofType(EpisodeReturnState.class);
        assertEquals(List.of("APPROACH", "EAT"), rows.stream().map(EpisodeReturnState::getAction).toList());
        assertEquals(List.of("REGULATED", "REGULATED"), rows.stream().map(EpisodeReturnState::getCloseType).toList());
        assertEquals(List.of(1L, 2L), rows.stream().map(EpisodeReturnState::getStepCycle).toList());
        assertEquals(1.0, rows.get(1).getValue(), TOL);
        assertEquals(GAMMA, rows.get(0).getValue(), TOL);
        // The close is at cycle 3: APPROACH (cycle 1) is two cycles from it, EAT (cycle 2) one.
        assertEquals(List.of(2L, 1L), rows.stream().map(EpisodeReturnState::getStepsToClose).toList());
        assertTrue(Double.isNaN(rows.get(0).getBootstrap()), "a regulated step is not pre-empted");

        // A cell nothing was decided in stays unseen.
        Estimate sleep = h.creature().operantStore().estimate(
                List.of(action(ActionType.SLEEP, null, 5)), HUNGRY).get(0);
        assertEquals(new Estimate(0, 1, 1), sleep);
    }

    // Review follow-up: a tracker event that breaks the buffer's invariants must not escape onReceive,
    // and the next episode must be learned from a clean buffer.
    @Test
    void brokenEventResetsTheBufferAndTheNextEpisodeStillLearns() {
        List<Object> messages = new ArrayList<>();
        messages.add(new FocusEvents(0, List.of(new FocusEvent.Resumed(0, HUNGER))));
        messages.addAll(inOrder());

        List<Estimate> recovered = estimates(drained(messages));

        assertEquals(estimates(drained(inOrder())), recovered);
    }

    // Review follow-up: the death close-out reaches the buffer and writes the open episode as DEATH.
    @Test
    void creatureDiedClosesTheOpenEpisodeAsDeath() {
        TestingHarness h = TestingHarness.builder().learningSettings(settings()).build();
        h.creature().memory().tickDecisionCycle();
        h.creature().valuation().tell(new FocusEvents(1, List.of(new FocusEvent.Opened(1, HUNGER))));
        h.creature().valuation().tell(new DecisionRecord(1, EAT, HUNGRY, HUNGER, Double.NaN));
        h.creature().valuation().tell(new br.cefetmg.lsi.l2l.creature.CreatureDied(1, new double[] {20, 0, 0, 0}));
        h.processUntilQuiescent();

        List<EpisodeReturnState> rows = h.bdSink().ofType(EpisodeReturnState.class);
        assertEquals(List.of("DEATH"), rows.stream().map(EpisodeReturnState::getCloseType).toList());
    }
}
