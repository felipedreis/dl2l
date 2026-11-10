package br.cefetmg.lsi.l2l.creature.arbiter;

import br.cefetmg.lsi.l2l.common.Constants;
import br.cefetmg.lsi.l2l.common.SequentialId;
import br.cefetmg.lsi.l2l.creature.actionSelector.ArbiterActionSelector;
import br.cefetmg.lsi.l2l.creature.actionSelector.SelectionContext;
import br.cefetmg.lsi.l2l.creature.arbiter.Arbiter.Controller;
import br.cefetmg.lsi.l2l.creature.arbiter.Arbiter.ControllerType;
import br.cefetmg.lsi.l2l.creature.arbiter.Arbiter.Params;
import br.cefetmg.lsi.l2l.creature.common.Action;
import br.cefetmg.lsi.l2l.creature.common.ActionType;
import br.cefetmg.lsi.l2l.creature.common.Perception;
import br.cefetmg.lsi.l2l.creature.components.Emotion;
import br.cefetmg.lsi.l2l.world.FruitType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Issue #105: {@link ExecutedEstimate#of} against real {@link Arbiter} decisions and real
 * {@link OperantEstimator}s. The fresh estimate of a non-candidate is the real
 * {@link ArbiterActionSelector#valueOf}, wrapped only to count its calls.
 */
class ExecutedEstimateTest {

    private static final double[] H = {0.0, 0.0, 0.0, 0.0};
    private static final String HUNGER = Constants.HUNGER;
    private static final Perception PERCEPTION = new Perception(FruitType.RED_APPLE, new SequentialId(1), 10, 0);

    private static Action on(ActionType type) {
        return new Action(type, PERCEPTION);
    }

    /** EAT has Q = 1, AVOID has Q = 0 (one cell each, two observations per cell). */
    private static OperantEstimator operant() {
        OperantEstimator e = new OperantEstimator(1.0);
        for (int i = 0; i < 20; i++) {
            e.record(on(ActionType.EAT), 2.0);
            e.record(on(ActionType.AVOID), 0.0);
        }
        return e;
    }

    private static final List<Action> CANDIDATES = List.of(on(ActionType.EAT), on(ActionType.AVOID));

    /** Pure greedy, so the pick is EAT and no random draw matters. */
    private static ArbitrationDecision greedyDecision(List<Controller> controllers) {
        return new Arbiter(controllers, new Params(0.8, 0.0, 0.0), new Random(1)).decide(CANDIDATES, H, HUNGER);
    }

    @Test
    void thePickIsResolvedFromTheDecisionWithoutAskingAgain() {
        OperantEstimator e = operant();
        ArbitrationDecision d = greedyDecision(List.of(new Controller(ControllerType.OPERANT, e)));
        AtomicInteger asked = new AtomicInteger();

        ExecutedEstimate x = ExecutedEstimate.of(d, on(ActionType.EAT), a -> {
            asked.incrementAndGet();
            return null;
        });

        assertEquals(ExecutedEstimate.Source.CHOSEN, x.source());
        assertEquals(d.chosenEstimate(), x.deciding());
        assertEquals(d.chosenEstimate(), x.byController().get(ControllerType.OPERANT));
        assertEquals(0, asked.get(), "the pick is in the decision: nothing is asked");
    }

    @Test
    void anotherCandidateIsReadFromTheDecisionOfEachConsultedController() {
        OperantEstimator e = operant();
        ArbitrationDecision d = greedyDecision(List.of(new Controller(ControllerType.OPERANT, e)));
        AtomicInteger asked = new AtomicInteger();

        ExecutedEstimate x = ExecutedEstimate.of(d, on(ActionType.AVOID), a -> {
            asked.incrementAndGet();
            return null;
        });

        assertEquals(ExecutedEstimate.Source.OTHER_CANDIDATE, x.source());
        assertEquals(d.estimates().get(1), x.deciding());
        assertEquals(e.estimate(CANDIDATES, H, HUNGER).get(1), x.byController().get(ControllerType.OPERANT));
        assertEquals(0, asked.get());
    }

    @Test
    void aNonCandidateIsAskedOnceUnderTheCheapestControllerOnly() {
        OperantEstimator e = operant();
        OperantEstimator other = new OperantEstimator(1.0);
        // The operant controller is decisive, so the episodic one (second, dearer) is never consulted.
        ArbiterActionSelector selector = new ArbiterActionSelector(List.of(), new Arbiter(
                List.of(new Controller(ControllerType.OPERANT, e), new Controller(ControllerType.EPISODIC, other)),
                new Params(0.8, 0.0, 0.0), new Random(1)));
        SelectionContext ctx = new SelectionContext(new Emotion(HUNGER), H, null, 1.0);
        Action approach = on(ActionType.APPROACH);
        selector.selectOne(CANDIDATES, ctx);
        ArbitrationDecision d = selector.takeLastDecision().orElseThrow();
        AtomicInteger asked = new AtomicInteger();

        ExecutedEstimate x = ExecutedEstimate.of(d, approach, a -> {
            asked.incrementAndGet();
            return selector.valueOf(a, ctx);
        });

        assertEquals(ExecutedEstimate.Source.VALUE_OF, x.source());
        assertEquals(1, asked.get(), "a non-candidate is estimated exactly once");
        assertEquals(e.estimate(List.of(approach), H, HUNGER).get(0), x.deciding());
        assertEquals(Set.of(ControllerType.OPERANT), x.byController().keySet(),
                "only the cheapest controller answered the fresh estimate");
        assertEquals(x.deciding(), x.byController().get(ControllerType.OPERANT));
    }

    @Test
    void nullInputsAreRejected() {
        ArbitrationDecision d = greedyDecision(List.of(new Controller(ControllerType.OPERANT, operant())));
        assertThrows(IllegalArgumentException.class, () -> ExecutedEstimate.of(null, on(ActionType.EAT), a -> null));
        assertThrows(IllegalArgumentException.class, () -> ExecutedEstimate.of(d, null, a -> null));
    }
}
