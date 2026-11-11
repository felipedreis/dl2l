package br.cefetmg.lsi.l2l.creature.testing;

import br.cefetmg.lsi.l2l.cluster.settings.LearningSettings;
import br.cefetmg.lsi.l2l.common.Constants;
import br.cefetmg.lsi.l2l.common.SequentialId;
import br.cefetmg.lsi.l2l.creature.arbiter.DecisionRecord;
import br.cefetmg.lsi.l2l.creature.arbiter.Estimate;
import br.cefetmg.lsi.l2l.creature.arbiter.FocusEvent;
import br.cefetmg.lsi.l2l.creature.memory.MemorySystem;
import br.cefetmg.lsi.l2l.creature.bd.EpisodeReturnState;
import br.cefetmg.lsi.l2l.creature.common.Action;
import br.cefetmg.lsi.l2l.creature.common.ActionType;
import br.cefetmg.lsi.l2l.creature.common.Perception;
import br.cefetmg.lsi.l2l.creature.conditioning.expectancy.ExpectancyMode;
import br.cefetmg.lsi.l2l.world.FruitType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Issue #110, component level: {@code Valuation} receives the decision, focus and outcome messages of
 * one episode and writes its closed steps to the {@code episode_return} table. The operant table is not fed
 * from them any more (it learns per outcome: {@code ValuationOperantTest}).
 *
 * <p>The decision cycle is held at 2 while the messages are drained, so each outcome is stamped 2 on
 * arrival in every ordering. Only the queue order differs: the outcome is delivered after the close, or
 * after the decision that follows the close. The buffer's settle rule must then give the same written
 * values as in order.
 *
 * <p>Expected values, with the default {@code HomeostaticValue} (set point 0.18, unit weights) and
 * {@code gamma = 2^(-1/5)} = {@code g}: hunger {@code h = 2}, so {@code d(h) = 1.82}. An EAT at cycle 2 with
 * outcome {@code K = -1} at cycle 2 has {@code G = -1} and value {@code 1.82 - 0.82 = 1.0}, giving
 * the written value 1.0. An APPROACH at cycle 1 has {@code G = -g} and value {@code g}.
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

    /** One producer call into the memory system, in the order a producer would make it. */
    private interface Call extends Consumer<MemorySystem> {
    }

    private static Call focus(FocusEvent e) {
        return m -> m.recordFocusEvents(List.of(e));
    }

    private static Call decision(int cycle, Action a, double[] h, String focal) {
        return m -> m.recordDecision(new DecisionRecord(cycle, a, h, focal, Double.NaN));
    }

    private static Call outcome(double[] k) {
        return m -> m.recordOutcome(k);
    }

    private static List<Call> inOrder() {
        return List.of(
                focus(new FocusEvent.Opened(1, HUNGER)),
                decision(1, APPROACH, HUNGRY, HUNGER),
                decision(2, EAT, HUNGRY, HUNGER),
                outcome(RELIEF),
                focus(new FocusEvent.Closed(3, HUNGER, FocusEvent.Outcome.REGULATED)),
                decision(3, WANDER, HUNGRY, null),
                decision(4, WANDER, HUNGRY, null));
    }

    /** The outcome overtakes nothing but arrives after the close event. */
    private static List<Call> outcomeAfterClose() {
        List<Call> m = new ArrayList<>(inOrder());
        Call k = m.remove(3);
        m.add(4, k);
        return m;
    }

    /** The outcome arrives after the decision that carries the close, but before the settling one. */
    private static List<Call> outcomeAfterCloseDecision() {
        List<Call> m = new ArrayList<>(inOrder());
        Call k = m.remove(3);
        m.add(5, k);
        return m;
    }

    /** Runs {@code messages} through a fresh creature's Valuation, with the decision cycle held at 2. */
    private static TestingHarness drained(List<Call> messages) {
        TestingHarness h = TestingHarness.builder().learningSettings(settings()).build();
        h.creature().memory().tickDecisionCycle();
        h.creature().memory().tickDecisionCycle();
        for (Call m : messages) {
            m.accept(h.creature().memory());
        }
        h.processUntilQuiescent();
        return h;
    }

    /** What the episode close-out wrote: one {@code action:value:closeType} entry per closed step. */
    private static List<String> written(TestingHarness h) {
        return h.bdSink().ofType(EpisodeReturnState.class).stream()
                .map(r -> r.getAction() + ":" + r.getValue() + ":" + r.getCloseType()).toList();
    }

    @Test
    void outOfOrderDeliveryWritesTheSameEpisodeReturnsAsInOrder() {
        List<String> ordered = written(drained(inOrder()));
        List<String> afterClose = written(drained(outcomeAfterClose()));
        List<String> afterClosingDecision = written(drained(outcomeAfterCloseDecision()));
        assertEquals(2, ordered.size());

        assertEquals(ordered, afterClose);
        assertEquals(ordered, afterClosingDecision);
    }

    @Test
    void closedEpisodeWritesItsReturnsButNoLongerTouchesTheOperantTable() {
        TestingHarness h = TestingHarness.builder().learningSettings(settings()).build();
        Estimate prior = new Estimate(0, 1, 1);
        assertEquals(prior, h.creature().operantStore().estimate(List.of(EAT), HUNGRY, HUNGER).get(0));

        h.creature().memory().tickDecisionCycle();
        h.creature().memory().tickDecisionCycle();
        for (Call m : inOrder()) {
            m.accept(h.creature().memory());
        }
        h.processUntilQuiescent();

        // The table learns per outcome (ValuationOperantTest), not from closed episodes: both cells stay at the prior.
        assertEquals(List.of(prior, prior),
                h.creature().operantStore().estimate(List.of(EAT, APPROACH), HUNGRY, HUNGER));
        assertTrue(h.bdSink().ofType(br.cefetmg.lsi.l2l.creature.bd.OperantUpdateState.class).isEmpty());

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

    }

    // Review follow-up: a tracker event that breaks the buffer's invariants must not escape onReceive,
    // and the next episode must be learned from a clean buffer.
    @Test
    void brokenEventResetsTheBufferAndTheNextEpisodeStillLearns() {
        List<Call> messages = new ArrayList<>();
        messages.add(focus(new FocusEvent.Resumed(0, HUNGER)));
        messages.addAll(inOrder());

        List<String> recovered = written(drained(messages));

        assertEquals(written(drained(inOrder())), recovered);
    }

    // Review follow-up: the death close-out reaches the buffer and writes the open episode as DEATH.
    @Test
    void creatureDiedClosesTheOpenEpisodeAsDeath() {
        TestingHarness h = TestingHarness.builder().learningSettings(settings()).build();
        h.creature().memory().tickDecisionCycle();
        focus(new FocusEvent.Opened(1, HUNGER)).accept(h.creature().memory());
        decision(1, EAT, HUNGRY, HUNGER).accept(h.creature().memory());
        h.creature().valuation().tell(new br.cefetmg.lsi.l2l.creature.CreatureDied(1, new double[] {20, 0, 0, 0}));
        h.processUntilQuiescent();

        List<EpisodeReturnState> rows = h.bdSink().ofType(EpisodeReturnState.class);
        assertEquals(List.of("DEATH"), rows.stream().map(EpisodeReturnState::getCloseType).toList());
    }
}
