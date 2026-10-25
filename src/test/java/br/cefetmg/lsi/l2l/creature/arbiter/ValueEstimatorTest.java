package br.cefetmg.lsi.l2l.creature.arbiter;

import br.cefetmg.lsi.l2l.common.SequentialId;
import br.cefetmg.lsi.l2l.creature.common.Action;
import br.cefetmg.lsi.l2l.creature.common.ActionType;
import br.cefetmg.lsi.l2l.creature.common.Perception;
import br.cefetmg.lsi.l2l.world.FruitType;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ValueEstimatorTest {

    private static final double[] H = {0.5, 0.2, 0.0, 0.1};

    private static Action action(ActionType type, int id) {
        return new Action(type, new Perception(FruitType.RED_APPLE, new SequentialId(id), 10, 0));
    }

    /** Test double: the same fixed estimate for every candidate. */
    static final class ConstantValueEstimator implements ValueEstimator {
        private final Estimate fixed;

        ConstantValueEstimator(Estimate fixed) {
            this.fixed = fixed;
        }

        @Override
        public List<Estimate> estimate(List<Action> candidates, double[] h) {
            return candidates.stream().map(c -> fixed).toList();
        }
    }

    /** Test double: a per-action-type estimate, so different candidates get different estimates. */
    static final class PerTypeValueEstimator implements ValueEstimator {
        private final Map<ActionType, Estimate> byType = new EnumMap<>(ActionType.class);

        PerTypeValueEstimator put(ActionType type, Estimate e) {
            byType.put(type, e);
            return this;
        }

        @Override
        public List<Estimate> estimate(List<Action> candidates, double[] h) {
            return candidates.stream().map(c -> byType.get(c.type)).toList();
        }
    }

    @Test
    void constantEstimatorReturnsOneEstimatePerCandidateInOrder() {
        Estimate fixed = new Estimate(2.0, 0.3, 0.5);
        List<Action> candidates = List.of(
                action(ActionType.EAT, 1), action(ActionType.APPROACH, 2), action(ActionType.WANDER, 3));

        List<Estimate> out = new ConstantValueEstimator(fixed).estimate(candidates, H);

        assertEquals(candidates.size(), out.size());
        for (Estimate e : out) {
            assertSame(fixed, e);
        }
    }

    @Test
    void emptyCandidatesGiveEmptyResult() {
        List<Estimate> out = new ConstantValueEstimator(new Estimate(1, 1, 1))
                .estimate(Collections.emptyList(), H);
        assertTrue(out.isEmpty());
    }

    @Test
    void differentCandidatesCanGetDifferentEstimates() {
        Estimate eat = new Estimate(3.0, 0.1, 0.4);
        Estimate avoid = new Estimate(-1.0, 0.6, 0.9);
        ValueEstimator estimator = new PerTypeValueEstimator()
                .put(ActionType.EAT, eat)
                .put(ActionType.AVOID, avoid);

        List<Estimate> out = estimator.estimate(
                List.of(action(ActionType.AVOID, 1), action(ActionType.EAT, 2)), H);

        assertEquals(2, out.size());
        assertSame(avoid, out.get(0));
        assertSame(eat, out.get(1));
    }
}
