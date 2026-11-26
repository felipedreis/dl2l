package br.cefetmg.lsi.l2l.creature.arbiter;

import br.cefetmg.lsi.l2l.common.SequentialId;
import br.cefetmg.lsi.l2l.creature.common.Action;
import br.cefetmg.lsi.l2l.creature.common.ActionType;
import br.cefetmg.lsi.l2l.creature.common.Perception;
import br.cefetmg.lsi.l2l.world.FruitType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests of {@link EpisodicEstimator} (issue #113): kNN value, n_eff, and the SE of sparse, distant and disagreeing memory. */
class EpisodicEstimatorTest {

    private static final double TOL = 1e-9;
    /** Hunger 3.0 over a set point of 0.18; the other drives at their set point. */
    private static final double[] H = {3.0, 0.18, 0.18, 0.18};
    private static final HomeostaticValue VALUE = HomeostaticValue.defaults();
    private static final EngramKeyEncoder ENCODER = new EngramKeyEncoder();
    private static final Action EAT = new Action(ActionType.EAT,
            new Perception(FruitType.RED_APPLE, new SequentialId(1), 5, 0));
    private static final double[] QUERY = ENCODER.encode(EAT.perception, H);

    /** An engram of {@code action} whose key is the query key moved by {@code delta} along feature 0, and whose V at H is {@code x}. */
    private static EpisodicEngram at(ActionType action, double delta, double x, double bootstrap) {
        double[] key = QUERY.clone();
        key[0] += delta;
        return new EpisodicEngram(action, "RED_APPLE", key, H, new double[]{-x, 0, 0, 0}, bootstrap, 1, "hunger",
                ClosedStep.CloseType.REGULATED, 1, 1);
    }

    private static EpisodicEngram at(double delta, double x) {
        return at(ActionType.EAT, delta, x, 0);
    }

    private static EpisodicEstimator with(List<EpisodicEngram> engrams) {
        EpisodicEstimator e = new EpisodicEstimator(ENCODER, VALUE);
        e.add(engrams);
        return e;
    }

    private static Estimate eat(EpisodicEstimator e) {
        return e.estimate(List.of(EAT), H, "hunger").get(0);
    }

    private static List<EpisodicEngram> tenAt(double delta, double x) {
        List<EpisodicEngram> list = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            list.add(at(delta, x));
        }
        return list;
    }

    @Test
    void noEngramsGiveTheUnseenPrior() {
        Estimate est = eat(new EpisodicEstimator(ENCODER, VALUE));
        assertEquals(0, est.q(), TOL);
        assertEquals(EpisodicEstimator.PRIOR_SIGMA, est.se(), TOL);
        assertEquals(EpisodicEstimator.PRIOR_SIGMA, est.sigma(), TOL);
        assertEquals(0, est.nEff(), TOL);
    }

    @Test
    void tenCloseAgreeingNeighboursGiveTheirValueAndANarrowSe() {
        Estimate est = eat(with(tenAt(0, 1.0)));
        assertEquals(1.0, est.q(), TOL);
        assertEquals(10.0, est.nEff(), TOL);
        // sigma^2 = (0.1^2 + 9 * 0) / 10; D = 0 so beta = 0; SE = sqrt(sigma^2 / 10)
        assertEquals(Math.sqrt(0.01 / 10 / 10), est.se(), TOL);
    }

    @Test
    void aSingleNeighbourIsWide() {
        Estimate one = eat(with(List.of(at(0, 1.0))));
        Estimate ten = eat(with(tenAt(0, 1.0)));
        assertEquals(1.0, one.nEff(), TOL);
        assertEquals(EpisodicEstimator.PRIOR_SIGMA, one.se(), TOL, "one memory: sigma = the prior, n_eff = 1, no distance");
        assertTrue(one.se() > 5 * ten.se(), "one " + one.se() + " ten " + ten.se());
    }

    @Test
    void distantNeighboursAreWideHoweverManyAndHoweverTheyAgree() {
        Estimate close = eat(with(tenAt(0.05, 1.0)));
        Estimate far = eat(with(tenAt(3.0, 1.0)));
        assertEquals(1.0, far.q(), TOL);
        // beta = L * Dbar = 0.5 * 3
        assertTrue(far.se() >= 1.5, "SE " + far.se());
        assertTrue(far.se() > 10 * close.se(), "close " + close.se() + " far " + far.se());
    }

    @Test
    void disagreeingNeighboursAreWiderThanAgreeingOnes() {
        List<EpisodicEngram> split = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            split.add(at(0, 2.0));
            split.add(at(0, 0.0));
        }
        Estimate disagree = eat(with(split));
        Estimate agree = eat(with(tenAt(0, 1.0)));
        assertEquals(1.0, disagree.q(), TOL);
        assertEquals(1.0, agree.q(), TOL);
        assertTrue(disagree.sigma() > agree.sigma());
        assertTrue(disagree.se() > 2 * agree.se(), "disagree " + disagree.se() + " agree " + agree.se());
    }

    @Test
    void oneCloseAndNineFarIsWideThroughTheDistanceBiasNotThroughNEff() {
        List<EpisodicEngram> list = new ArrayList<>();
        list.add(at(0, 1.0));
        for (int i = 0; i < 9; i++) {
            list.add(at(5.0, 1.0));
        }
        Estimate est = eat(with(list));
        // The median bandwidth adapts to the far majority, so n_eff stays high: the paper's "~ 1" does not hold ...
        assertTrue(est.nEff() > 9.0, "n_eff " + est.nEff());
        // ... and it is beta = L * Dbar that keeps the error bar wide.
        assertTrue(est.se() > 2.0, "SE " + est.se());
    }

    @Test
    void neighboursAreWeightedByDistance() {
        // b is the median distance (0.5 here): the closer memory counts more than the farther one.
        EpisodicEstimator e = with(List.of(at(0.1, 1.0), at(0.5, 0.0), at(0.9, 0.0)));
        Estimate est = eat(e);
        double b = 0.5;
        double w1 = Math.exp(-0.1 * 0.1 / (2 * b * b));
        double w2 = Math.exp(-0.5 * 0.5 / (2 * b * b));
        double w3 = Math.exp(-0.9 * 0.9 / (2 * b * b));
        assertEquals(w1 / (w1 + w2 + w3), est.q(), 1e-6);
        assertTrue(est.q() > 1.0 / 3);
    }

    @Test
    void onlyTheMNearestAreUsed() {
        List<EpisodicEngram> list = tenAt(0.2, 1.0);
        for (int i = 0; i < 5; i++) {
            list.add(at(4.0, -1.0));
        }
        Estimate est = eat(with(list));
        assertEquals(1.0, est.q(), TOL);
        assertEquals(10.0, est.nEff(), TOL);
    }

    @Test
    void valuesAreReadAgainstTheDrivesNowNotThoseAtWriteTime() {
        EpisodicEstimator e = with(tenAt(0, 1.0)); // G = -1 on hunger, written when hunger was 3.0
        assertEquals(1.0, eat(e).q(), TOL);
        // Now hunger is at its set point: nothing left to reduce, the same memory is worth 0.
        double[] sated = {0.18, 0.18, 0.18, 0.18};
        Estimate satedEstimate = e.estimate(List.of(EAT), sated, "hunger").get(0);
        // (the key moves with h as well, but every memory is equally far, so Q is the value alone)
        assertEquals(0.0, satedEstimate.q(), TOL);
    }

    @Test
    void aBootstrapTermIsAddedAndAZeroReturnIsWorthNothing() {
        List<EpisodicEngram> list = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            list.add(at(ActionType.EAT, 0, 1.0, 0.25));
        }
        assertEquals(1.25, eat(with(list)).q(), TOL);
        List<EpisodicEngram> zero = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            zero.add(at(ActionType.EAT, 0, 0.0, 0));
        }
        assertEquals(0.0, eat(with(zero)).q(), TOL);
    }

    @Test
    void anActionReadsOnlyItsOwnEngrams() {
        List<EpisodicEngram> list = tenAt(0, 1.0);
        for (int i = 0; i < 10; i++) {
            list.add(at(ActionType.APPROACH, 0, -1.0, 0));
        }
        EpisodicEstimator e = with(list);
        Action approach = new Action(ActionType.APPROACH, EAT.perception);
        List<Estimate> est = e.estimate(List.of(EAT, approach), H, null);
        assertEquals(1.0, est.get(0).q(), TOL);
        assertEquals(-1.0, est.get(1).q(), TOL);
        Action avoid = new Action(ActionType.AVOID, EAT.perception);
        assertEquals(0.0, e.estimate(List.of(avoid), H, null).get(0).nEff(), TOL);
    }

    @Test
    void estimateIsReadOnlyAndDeterministic() {
        EpisodicEstimator e = with(tenAt(0.3, 0.7));
        int before = e.size();
        double[] h = H.clone();
        Estimate a = eat(e);
        Estimate b = eat(e);
        assertEquals(a, b);
        assertEquals(before, e.size());
        assertEquals(List.of(3.0, 0.18, 0.18, 0.18), List.of(h[0], h[1], h[2], h[3]));
    }

    @Test
    void identicalKeysDoNotDivideByZero() {
        Estimate est = eat(with(List.of(at(0, 1.0), at(0, 0.5))));
        assertEquals(0.75, est.q(), TOL);
        assertTrue(Double.isFinite(est.se()));
    }

    @Test
    void rejectsBadArguments() {
        assertThrows(IllegalArgumentException.class, () -> new EpisodicEstimator(null, ENCODER, VALUE, 10, 0.5));
        assertThrows(IllegalArgumentException.class,
                () -> new EpisodicEstimator(new EpisodicEngramStore(), ENCODER, VALUE, 0, 0.5));
        assertThrows(IllegalArgumentException.class,
                () -> new EpisodicEstimator(new EpisodicEngramStore(), ENCODER, VALUE, 10, -1));
        assertThrows(IllegalArgumentException.class, () -> new EpisodicEstimator(ENCODER, VALUE).estimate(null, H, null));
    }
}
