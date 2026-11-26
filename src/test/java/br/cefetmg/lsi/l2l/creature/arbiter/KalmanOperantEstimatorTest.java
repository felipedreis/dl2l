package br.cefetmg.lsi.l2l.creature.arbiter;

import br.cefetmg.lsi.l2l.common.SequentialId;
import br.cefetmg.lsi.l2l.creature.common.Action;
import br.cefetmg.lsi.l2l.creature.common.ActionType;
import br.cefetmg.lsi.l2l.creature.common.Perception;
import br.cefetmg.lsi.l2l.world.FruitType;
import br.cefetmg.lsi.l2l.world.WorldObjectType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link KalmanOperantEstimator}: the update equations by hand, the pooled prior, abstention and the per-object clock. */
class KalmanOperantEstimatorTest {

    private static final double TAU2 = 1e-3;
    private static final double S2_0 = 0.01;
    /** The default pooled-variance floor for S2_0: 16 prior variances. */
    private static final double POOL_FLOOR = 16 * S2_0;
    private static final double TOL = 1e-9;
    private static final double[] H = {0, 0, 0, 0};

    /** Decay 1 and a floor far below the numbers used, so the hand values hold exactly. */
    private static KalmanOperantEstimator estimator() {
        return new KalmanOperantEstimator(TAU2, S2_0, 1e-4, 1.0);
    }

    private static Action act(ActionType type, WorldObjectType obj) {
        return new Action(type, new Perception(obj, new SequentialId(1), 10, 0));
    }

    private static Estimate of(KalmanOperantEstimator e, ActionType type, WorldObjectType obj) {
        return e.estimate(List.of(act(type, obj)), H, null).get(0);
    }

    @Test
    void anActionNeverTriedOnAnythingAbstains() {
        KalmanOperantEstimator e = estimator();
        Estimate est = of(e, ActionType.EAT, FruitType.RED_APPLE);
        assertTrue(est.abstains());
        assertEquals(Estimate.Basis.ABSTAIN, est.basis());
    }

    @Test
    void theFirstOutcomeOfAnAbstainingCellIsTheValueWithTheOutcomeSpreadAsUncertainty() {
        KalmanOperantEstimator e = estimator();
        OperantUpdate u = e.record(FruitType.RED_APPLE, ActionType.EAT, 0.3);
        assertTrue(u.prior().abstains(), "it predicted nothing before");
        assertEquals(0.3, u.estimate().q(), TOL);
        assertEquals(Math.sqrt(S2_0), u.estimate().se(), TOL);
        assertEquals(Estimate.Basis.OBSERVED, u.estimate().basis());
    }

    @Test
    void theSecondOutcomeFollowsTheKalmanEquationsByHand() {
        KalmanOperantEstimator e = estimator();
        e.record(FruitType.RED_APPLE, ActionType.EAT, 0.3);          // m = 0.3, P = 0.01
        OperantUpdate u = e.record(FruitType.RED_APPLE, ActionType.EAT, 0.1);
        // P += tau2 -> 0.011; s2 = prior 0.01 (one observation); k = 0.011 / 0.021; m = 0.3 + k (0.1 - 0.3); P = 0.011 (1 - k)
        double k = 0.011 / 0.021;
        assertEquals(0.3 + k * (0.1 - 0.3), u.estimate().q(), TOL);
        assertEquals(Math.sqrt(0.011 * (1 - k)), u.estimate().se(), TOL);
        assertEquals(0.3, u.prior().q(), TOL, "the prior is what the cell predicted for this outcome");
    }

    @Test
    void anUntriedCellStartsFromWhatTheSameActionDidOnOtherObjects() {
        KalmanOperantEstimator e = estimator();
        e.record(FruitType.RED_APPLE, ActionType.EAT, 0.3);
        Estimate pooled = of(e, ActionType.EAT, FruitType.GREEN_APPLE);
        assertEquals(Estimate.Basis.POOLED_PRIOR, pooled.basis());
        assertEquals(0.3, pooled.q(), TOL);
        // One other cell: no between-object spread, so the variance is the pooled floor plus one drift step.
        assertEquals(Math.sqrt(POOL_FLOOR + TAU2), pooled.se(), TOL);
        assertEquals(Math.sqrt(S2_0), pooled.sigma(), TOL);
    }

    @Test
    void thePooledVarianceGrowsWithTheSpreadBetweenObjects() {
        KalmanOperantEstimator e = estimator();
        e.record(FruitType.RED_APPLE, ActionType.EAT, 0.5);          // m = 0.5
        e.record(FruitType.GREEN_APPLE, ActionType.EAT, -0.3);       // starts from the pool (0.5, P = floor + tau2), then the gain
        double k = (POOL_FLOOR + TAU2) / (POOL_FLOOR + TAU2 + S2_0);
        double green = 0.5 + k * (-0.3 - 0.5);
        double mean = (0.5 + green) / 2;
        double between = (Math.pow(0.5 - mean, 2) + Math.pow(green - mean, 2)) / 1;
        Estimate pooled = of(e, ActionType.EAT, br.cefetmg.lsi.l2l.world.PlantType.CACTUS);
        assertEquals(mean, pooled.q(), TOL);
        assertEquals(Math.sqrt(Math.max(between, POOL_FLOOR) + TAU2), pooled.se(), TOL);
    }

    @Test
    void anOutcomeOnTheObjectAgesTheOtherCellsOfThatObjectOnly() {
        KalmanOperantEstimator e = estimator();
        e.record(FruitType.RED_APPLE, ActionType.TOUCH, 0.1);
        e.record(FruitType.GREEN_APPLE, ActionType.TOUCH, 0.1);
        double red0 = Math.pow(of(e, ActionType.TOUCH, FruitType.RED_APPLE).se(), 2);
        double green0 = Math.pow(of(e, ActionType.TOUCH, FruitType.GREEN_APPLE).se(), 2);
        e.record(FruitType.RED_APPLE, ActionType.EAT, 0.3);   // two outcomes on RED_APPLE by another action
        e.record(FruitType.RED_APPLE, ActionType.EAT, 0.3);
        // RED_APPLE/TOUCH aged by exactly two drift steps; GREEN_APPLE/TOUCH, on another object, did not age.
        assertEquals(red0 + 2 * TAU2, Math.pow(of(e, ActionType.TOUCH, FruitType.RED_APPLE).se(), 2), TOL);
        assertEquals(green0, Math.pow(of(e, ActionType.TOUCH, FruitType.GREEN_APPLE).se(), 2), TOL);
    }

    @Test
    void uncertaintyGrowsWhenACellIsNotObservedWhileItsObjectKeepsYieldingOutcomes() {
        KalmanOperantEstimator e = estimator();
        e.record(FruitType.RED_APPLE, ActionType.TOUCH, 0.1);
        double se0 = of(e, ActionType.TOUCH, FruitType.RED_APPLE).se();
        for (int i = 0; i < 50; i++) {
            e.record(FruitType.RED_APPLE, ActionType.EAT, 0.3);
        }
        assertTrue(of(e, ActionType.TOUCH, FruitType.RED_APPLE).se() > se0, "a stale cell must be less sure, not more");
    }

    @Test
    void theGainConvergesToTheSteadyStateTheTauChoiceTargets() {
        // tau2 = k*^2 s2 / (1 - k*) gives a steady-state gain k*: here s2 = 0.01 and tau2 = 8e-4 -> k* = 0.25.
        KalmanOperantEstimator e = new KalmanOperantEstimator(8e-4, 0.01, 1e-4, 1.0);
        double previous = 0.0;
        double gain = 0.0;
        for (int i = 0; i < 400; i++) {
            double before = of(e, ActionType.EAT, FruitType.RED_APPLE).abstains() ? 0.0 : of(e, ActionType.EAT, FruitType.RED_APPLE).q();
            double v = (i % 2 == 0) ? 0.2 : 0.4;   // spread 0.01 once the Welford estimate settles
            OperantUpdate u = e.record(FruitType.RED_APPLE, ActionType.EAT, v);
            if (i > 300) {
                gain = (u.estimate().q() - before) / (v - before);
            }
            previous = u.estimate().q();
        }
        assertTrue(gain > 0.15 && gain < 0.35, "steady-state gain should be near 0.25, was " + gain);
        assertTrue(previous > 0.2 && previous < 0.4);
    }

    @Test
    void aWorseOutcomeLowersTheValueAndAGoodOneRaisesIt() {
        KalmanOperantEstimator e = estimator();
        e.record(FruitType.RED_APPLE, ActionType.EAT, 0.2);
        double up = e.record(FruitType.RED_APPLE, ActionType.EAT, 0.4).estimate().q();
        double down = e.record(FruitType.RED_APPLE, ActionType.EAT, -0.4).estimate().q();
        assertTrue(up > 0.2 && down < up);
    }

    @Test
    void approachAndTheCreatureItselfAbstainWithNoSpecialCase() {
        KalmanOperantEstimator e = estimator();
        e.record(FruitType.RED_APPLE, ActionType.EAT, 0.3);   // Valuation records only outcomes on fruit and plants
        assertTrue(of(e, ActionType.APPROACH, FruitType.RED_APPLE).abstains());
        assertTrue(of(e, ActionType.WANDER, null).abstains());
        assertTrue(of(e, ActionType.SLEEP, null).abstains());
    }

    @Test
    void theSpreadNeverReachesZeroSoTheSeStaysStrictlyPositive() {
        KalmanOperantEstimator e = new KalmanOperantEstimator(TAU2, S2_0, 0.05 * 0.05, 1.0);
        for (int i = 0; i < 200; i++) {
            e.record(FruitType.RED_APPLE, ActionType.EAT, 0.3);   // constant outcomes: zero sample spread
        }
        Estimate est = of(e, ActionType.EAT, FruitType.RED_APPLE);
        assertTrue(est.se() > 0 && est.sigma() >= 0.05 - 1e-12);
    }

    @Test
    void invalidInputIsRejectedAndTheTableIsUnchanged() {
        KalmanOperantEstimator e = estimator();
        assertThrows(IllegalArgumentException.class, () -> e.record(FruitType.RED_APPLE, null, 0.1));
        assertThrows(IllegalArgumentException.class, () -> e.record(FruitType.RED_APPLE, ActionType.EAT, Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> e.record(FruitType.RED_APPLE, ActionType.EAT, Double.POSITIVE_INFINITY));
        assertTrue(of(e, ActionType.EAT, FruitType.RED_APPLE).abstains());
        assertThrows(IllegalArgumentException.class, () -> new KalmanOperantEstimator(0, S2_0, 1e-4, 1.0));
        assertThrows(IllegalArgumentException.class, () -> new KalmanOperantEstimator(TAU2, S2_0, 1e-4, 1.5));
    }
}
