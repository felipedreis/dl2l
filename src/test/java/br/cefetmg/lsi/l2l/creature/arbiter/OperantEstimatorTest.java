package br.cefetmg.lsi.l2l.creature.arbiter;

import br.cefetmg.lsi.l2l.common.SequentialId;
import br.cefetmg.lsi.l2l.creature.common.Action;
import br.cefetmg.lsi.l2l.creature.common.ActionType;
import br.cefetmg.lsi.l2l.creature.common.Perception;
import br.cefetmg.lsi.l2l.world.FruitType;
import br.cefetmg.lsi.l2l.world.WorldObjectType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests of {@link OperantEstimator} and {@link WelfordTable} against the real implementation.
 * Default distance edges are 50 and 100 (one third and two thirds of a 150 vision radius).
 */
class OperantEstimatorTest {

    private static final String HUNGER = "hunger";
    private static final String SLEEP = "sleep";
    private static final double[] H = {0.0, 0.0, 0.0, 0.0};
    private static final double TOL = 1e-9;

    private static Action act(ActionType type, WorldObjectType obj, double distance, int id) {
        return new Action(type, new Perception(obj, new SequentialId(id), distance, 0));
    }

    private static Action noObject(ActionType type, double distance, int id) {
        return act(type, null, distance, id);
    }

    private static Estimate one(ValueEstimator e, Action a) {
        return e.estimate(List.of(a), H).get(0);
    }

    private static OperantEstimator withDrive(String drive) {
        return new OperantEstimator(() -> drive);
    }

    // ---- Done-when property (#88) ----------------------------------------------------------

    @Test
    void constantPositiveValueShrinksQAndNarrowsSeAtEveryStep() {
        OperantEstimator e = withDrive(HUNGER);
        Action eat = act(ActionType.EAT, FruitType.RED_APPLE, 10, 1);
        double prevQ = Double.NEGATIVE_INFINITY;
        double prevSe = Double.POSITIVE_INFINITY;
        Estimate last = null;
        for (int k = 1; k <= 50; k++) {
            e.record(HUNGER, eat, 0.5);
            last = one(e, eat);
            assertTrue(last.q() <= 0.5, "Q must never exceed the mean: " + last.q());
            assertTrue(last.q() >= prevQ, "Q must rise monotonically toward the mean at step " + k);
            assertTrue(last.se() > 0, "SE never collapses to 0, even for a constant outcome");
            assertTrue(last.se() < prevSe, "SE must narrow strictly at step " + k);
            prevQ = last.q();
            prevSe = last.se();
        }
        assertTrue(last.q() > 0 && last.q() < 0.5, "Q after 50 must be shrunk below 0.5: " + last.q());
    }

    @Test
    void constantNegativeValueShrinksQTowardMeanFromBelow() {
        OperantEstimator e = withDrive(HUNGER);
        Action eat = act(ActionType.EAT, FruitType.ROTTEN_APPLE, 10, 1);
        double prevQ = Double.POSITIVE_INFINITY;
        double prevSe = Double.POSITIVE_INFINITY;
        for (int k = 1; k <= 50; k++) {
            e.record(HUNGER, eat, -0.5);
            Estimate est = one(e, eat);
            assertTrue(est.q() >= -0.5, "Q must never fall below the mean: " + est.q());
            assertTrue(est.q() <= prevQ, "Q must fall monotonically toward the mean at step " + k);
            assertTrue(est.se() > 0, "SE never collapses to 0, even for a constant outcome");
            assertTrue(est.se() < prevSe, "SE must narrow strictly at step " + k);
            prevQ = est.q();
            prevSe = est.se();
        }
    }

    @Test
    void equalMeanCellsWithFewerObservationsHaveSmallerMagnitudeQ() {
        for (double mean : new double[] {0.5, -0.5}) {
            OperantEstimator e = withDrive(HUNGER);
            Action few = act(ActionType.EAT, FruitType.RED_APPLE, 10, 1);
            Action many = act(ActionType.EAT, FruitType.GREEN_APPLE, 10, 2);
            for (int i = 0; i < 5; i++) {
                e.record(HUNGER, few, mean);
            }
            for (int i = 0; i < 50; i++) {
                e.record(HUNGER, many, mean);
            }
            double q5 = one(e, few).q();
            double q50 = one(e, many).q();
            assertTrue(Math.abs(q5) <= Math.abs(q50), "|Q_5| <= |Q_50| for mean " + mean);
            assertTrue(Math.abs(q50) <= Math.abs(mean), "|Q_50| <= |mean| for mean " + mean);
        }
    }

    // ---- Unseen cells, Welford, decay ------------------------------------------------------

    @Test
    void unseenCellIsUninformed() {
        Estimate e = one(withDrive(HUNGER), act(ActionType.EAT, FruitType.RED_APPLE, 10, 1));
        assertEquals(new Estimate(0, 1, 1), e);
    }

    @Test
    void decayOneMatchesBatchMeanAndSampleSd() {
        Random rnd = new Random(42);
        List<Double> values = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            values.add(rnd.nextGaussian() * 2 + 0.7);
        }
        OperantEstimator e = new OperantEstimator(() -> HUNGER, new double[] {50, 100}, 1.0);
        Action eat = act(ActionType.EAT, FruitType.RED_APPLE, 10, 1);
        for (double v : values) {
            e.record(HUNGER, eat, v);
        }

        int n = values.size();
        double mean = values.stream().mapToDouble(Double::doubleValue).sum() / n;
        double ss = values.stream().mapToDouble(v -> (v - mean) * (v - mean)).sum();
        double sd = Math.sqrt(ss / (n - 1));

        Estimate est = one(e, eat);
        assertEquals(n * mean / (n + 1), est.q(), TOL);
        double sigma = Math.sqrt((1.0 + (n - 1) * sd * sd) / n);
        assertEquals(sigma, est.sigma(), TOL);
        assertEquals(sigma / Math.sqrt(n + 1), est.se(), TOL);
        assertEquals(sd, est.sigma(), 0.01, "converges to the sample SD with much evidence");
    }

    @Test
    void decayedCountFollowsClosedForm() {
        double decay = 0.9;
        OperantEstimator e = new OperantEstimator(() -> HUNGER, new double[] {50, 100}, decay);
        Action eat = act(ActionType.EAT, FruitType.RED_APPLE, 10, 1);
        for (int k = 1; k <= 7; k++) {
            e.record(HUNGER, eat, 1.0);
            // With constant V = 1 the mean is 1, so Q = n / (n + 1) and n = Q / (1 - Q).
            double q = one(e, eat).q();
            double n = q / (1 - q);
            double closedForm = (1 - Math.pow(decay, k)) / (1 - decay);
            assertEquals(closedForm, n, TOL, "decayed count after " + k + " updates");
        }
    }

    @Test
    void defaultDecayIsHalfPercentLessThanOne() {
        Action eat = act(ActionType.EAT, FruitType.RED_APPLE, 10, 1);
        OperantEstimator defaults = withDrive(HUNGER);
        OperantEstimator explicit = new OperantEstimator(() -> HUNGER, new double[] {50, 100}, 0.995);
        OperantEstimator noForgetting = new OperantEstimator(() -> HUNGER, new double[] {50, 100}, 1.0);
        for (int i = 0; i < 30; i++) {
            double v = i % 3 == 0 ? 1.0 : -0.25;
            defaults.record(HUNGER, eat, v);
            explicit.record(HUNGER, eat, v);
            noForgetting.record(HUNGER, eat, v);
        }
        assertEquals(one(explicit, eat), one(defaults, eat));
        assertTrue(!one(noForgetting, eat).equals(one(defaults, eat)), "default must forget, unlike decay 1");
    }

    @Test
    void oldEvidenceFadesUnderDecay() {
        Action eat = act(ActionType.EAT, FruitType.RED_APPLE, 10, 1);
        OperantEstimator decayed = new OperantEstimator(() -> HUNGER, new double[] {50, 100}, 0.9);
        OperantEstimator kept = new OperantEstimator(() -> HUNGER, new double[] {50, 100}, 1.0);
        decayed.record(HUNGER, eat, 100.0);
        kept.record(HUNGER, eat, 100.0);
        for (int i = 0; i < 50; i++) {
            decayed.record(HUNGER, eat, 0.0);
            kept.record(HUNGER, eat, 0.0);
        }
        // Undecayed mean is 100/51 (about 1.96); with 0.9 the outlier's weight is 0.9^50 (about 0.005).
        assertTrue(one(kept, eat).q() > 1.5, "without decay the outlier keeps its influence");
        assertTrue(one(decayed, eat).q() < 0.1, "with decay the outlier has faded");
    }

    // ---- Sigma prior and SE ----------------------------------------------------------------

    @Test
    void sigmaStaysAtPriorBeforeThreeObservations() {
        OperantEstimator e = new OperantEstimator(() -> HUNGER, new double[] {50, 100}, 1.0);
        Action eat = act(ActionType.EAT, FruitType.RED_APPLE, 10, 1);
        e.record(HUNGER, eat, 0.3);
        e.record(HUNGER, eat, 0.3);
        Estimate est = one(e, eat);
        assertEquals(1.0, est.sigma(), 0.0, "identical values still leave the prior sigma0 = 1");
        assertEquals(0.3 * 2 / 3, est.q(), TOL);
        assertEquals(1 / Math.sqrt(3), est.se(), TOL);
    }

    @Test
    void sigmaBlendsSampleVarianceWithThePriorFromThreeObservations() {
        OperantEstimator e = new OperantEstimator(() -> HUNGER, new double[] {50, 100}, 1.0);
        Action eat = act(ActionType.EAT, FruitType.RED_APPLE, 10, 1);
        for (double v : new double[] {1, 2, 4}) {
            e.record(HUNGER, eat, v);
        }
        // mean 7/3; sample variance 7/3; blended with the prior (1 observation of variance 1):
        // (1 + 2 * 7/3) / 3.
        assertEquals(Math.sqrt((1 + 2 * 7.0 / 3.0) / 3), one(e, eat).sigma(), TOL);
    }

    @Test
    void constantOutcomeKeepsSigmaPositiveSoCalibrationAndDecisivenessStayDefined() {
        OperantEstimator e = new OperantEstimator(() -> HUNGER, new double[] {50, 100}, 1.0);
        Action eat = act(ActionType.EAT, FruitType.RED_APPLE, 10, 1);
        for (int i = 0; i < 100; i++) {
            e.record(HUNGER, eat, 0.5);
        }
        Estimate est = one(e, eat);
        assertTrue(est.sigma() > 0 && est.se() > 0);
        assertTrue(est.sigma() < 0.2, "and still shrinks with evidence: " + est.sigma());
    }

    @Test
    void sigmaDoesNotShrinkWithEvidenceButSeDoes() {
        OperantEstimator e = new OperantEstimator(() -> HUNGER, new double[] {50, 100}, 1.0);
        Action eat = act(ActionType.EAT, FruitType.RED_APPLE, 10, 1);
        for (int i = 0; i < 4; i++) {
            e.record(HUNGER, eat, i % 2);
        }
        Estimate early = one(e, eat);
        for (int i = 4; i < 40; i++) {
            e.record(HUNGER, eat, i % 2);
        }
        Estimate late = one(e, eat);
        assertTrue(late.se() < early.se() / 2, "SE shrinks with evidence");
        assertEquals(0.5, late.sigma(), 0.05, "sigma converges to the outcome's own spread, not to 0");
        assertTrue(late.sigma() > 0.4, "outcome stays noisy");
    }

    // ---- Cells, bins, NONE buckets ---------------------------------------------------------

    @Test
    void cellsAreSeparatedByDriveObjectBinAndAction() {
        String[] drive = {HUNGER};
        OperantEstimator e = new OperantEstimator(() -> drive[0]);
        Action eat = act(ActionType.EAT, FruitType.RED_APPLE, 10, 1);
        for (int i = 0; i < 5; i++) {
            e.record(HUNGER, eat, 1.0);
        }
        Estimate seen = one(e, eat);
        assertTrue(seen.q() > 0);

        drive[0] = SLEEP;
        assertEquals(new Estimate(0, 1, 1), one(e, eat), "other focal drive");
        drive[0] = null;
        assertEquals(new Estimate(0, 1, 1), one(e, eat), "no focal drive is its own cell");
        drive[0] = HUNGER;
        assertEquals(seen, one(e, eat), "same drive reads the same cell");

        assertEquals(new Estimate(0, 1, 1), one(e, act(ActionType.EAT, FruitType.GREEN_APPLE, 10, 2)), "object type");
        assertEquals(new Estimate(0, 1, 1), one(e, act(ActionType.EAT, FruitType.RED_APPLE, 120, 3)), "distance bin");
        assertEquals(new Estimate(0, 1, 1), one(e, act(ActionType.APPROACH, FruitType.RED_APPLE, 10, 4)), "action type");
    }

    @Test
    void distanceBinBoundariesAreInclusiveOnTheLowerBin() {
        OperantEstimator e = withDrive(HUNGER);
        Estimate unseen = new Estimate(0, 1, 1);
        e.record(HUNGER, act(ActionType.EAT, FruitType.RED_APPLE, 50, 1), 1.0);
        assertTrue(one(e, act(ActionType.EAT, FruitType.RED_APPLE, 49, 2)).q() > 0, "49 is in bin 0 with 50");
        assertEquals(unseen, one(e, act(ActionType.EAT, FruitType.RED_APPLE, 50.5, 3)), "50.5 is bin 1");
        assertEquals(unseen, one(e, act(ActionType.EAT, FruitType.RED_APPLE, 100, 4)), "100 is bin 1");

        e.record(HUNGER, act(ActionType.EAT, FruitType.RED_APPLE, 100, 5), 1.0);
        assertTrue(one(e, act(ActionType.EAT, FruitType.RED_APPLE, 100, 6)).q() > 0, "100 is bin 1, now observed");
        assertEquals(unseen, one(e, act(ActionType.EAT, FruitType.RED_APPLE, 100.5, 9)), "100.5 is bin 2, still empty");
        assertEquals(one(e, act(ActionType.EAT, FruitType.RED_APPLE, 60, 7)).q(),
                one(e, act(ActionType.EAT, FruitType.RED_APPLE, 100, 8)).q(), TOL,
                "bin 1 holds both 60 and 100");
    }

    @Test
    void customEdgesDefineTheBins() {
        OperantEstimator e = new OperantEstimator(() -> HUNGER, new double[] {10}, 1.0);
        e.record(HUNGER, act(ActionType.EAT, FruitType.RED_APPLE, 10, 1), 1.0);
        assertTrue(one(e, act(ActionType.EAT, FruitType.RED_APPLE, 3, 2)).q() > 0, "10 and below share bin 0");
        assertEquals(new Estimate(0, 1, 1), one(e, act(ActionType.EAT, FruitType.RED_APPLE, 10.1, 3)), "above 10 is bin 1");
    }

    @Test
    void candidateWithoutObjectUsesNoneBucketAndBinZero() {
        OperantEstimator e = withDrive(HUNGER);
        e.record(HUNGER, noObject(ActionType.WANDER, 10, 1), 0.8);
        assertTrue(one(e, noObject(ActionType.WANDER, 140, 2)).q() > 0, "distance is ignored: bin 0 for every distance");
        assertEquals(new Estimate(0, 1, 1), one(e, act(ActionType.WANDER, FruitType.RED_APPLE, 10, 3)),
                "an object-bearing WANDER is a different cell");
    }

    @Test
    void noObjectCandidatesDoNotCollideWithObjectCells() {
        OperantEstimator e = withDrive(HUNGER);
        e.record(HUNGER, noObject(ActionType.EAT, 10, 1), 0.8);
        assertEquals(new Estimate(0, 1, 1), one(e, act(ActionType.EAT, FruitType.RED_APPLE, 10, 2)));
    }

    // ---- estimate: order, size, empty, read-only -------------------------------------------

    @Test
    void estimatePreservesOrderAndSize() {
        OperantEstimator e = withDrive(HUNGER);
        Action eat = act(ActionType.EAT, FruitType.RED_APPLE, 10, 1);
        Action approach = act(ActionType.APPROACH, FruitType.GREEN_APPLE, 10, 2);
        Action wander = noObject(ActionType.WANDER, 10, 3);
        e.record(HUNGER, eat, 1.0);
        e.record(HUNGER, approach, -1.0);
        e.record(HUNGER, wander, 0.25);

        List<Action> candidates = List.of(wander, eat, approach, eat);
        List<Estimate> out = e.estimate(candidates, H);
        assertEquals(4, out.size());
        assertEquals(one(e, wander), out.get(0));
        assertEquals(one(e, eat), out.get(1));
        assertEquals(one(e, approach), out.get(2));
        assertEquals(one(e, eat), out.get(3));
    }

    @Test
    void estimateOfEmptyListIsEmpty() {
        assertTrue(withDrive(HUNGER).estimate(List.of(), H).isEmpty());
    }

    @Test
    void estimateResultIsUnmodifiable() {
        List<Estimate> out = withDrive(HUNGER).estimate(
                List.of(act(ActionType.EAT, FruitType.RED_APPLE, 10, 1)), H);
        assertThrows(UnsupportedOperationException.class, () -> out.add(new Estimate(0, 1, 1)));
    }

    @Test
    void estimateIsReadOnly() {
        Action eat = act(ActionType.EAT, FruitType.RED_APPLE, 10, 1);
        OperantEstimator touched = withDrive(HUNGER);
        OperantEstimator untouched = withDrive(HUNGER);
        for (int i = 0; i < 10; i++) {
            one(touched, eat);
            one(touched, act(ActionType.APPROACH, FruitType.GREEN_APPLE, 10, 2));
        }
        for (int i = 0; i < 3; i++) {
            touched.record(HUNGER, eat, 0.4);
            untouched.record(HUNGER, eat, 0.4);
        }
        assertEquals(one(untouched, eat), one(touched, eat));
        assertEquals(new Estimate(0, 1, 1), one(touched, act(ActionType.APPROACH, FruitType.GREEN_APPLE, 10, 2)),
                "estimating an unseen cell does not insert it");
    }

    // ---- record: validation ---------------------------------------------------------------

    @Test
    void recordRejectsNonFiniteValuesAndLeavesTableUnchanged() {
        OperantEstimator e = withDrive(HUNGER);
        Action eat = act(ActionType.EAT, FruitType.RED_APPLE, 10, 1);
        assertThrows(IllegalArgumentException.class, () -> e.record(HUNGER, eat, Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> e.record(HUNGER, eat, Double.POSITIVE_INFINITY));
        assertThrows(IllegalArgumentException.class, () -> e.record(HUNGER, eat, Double.NEGATIVE_INFINITY));
        assertEquals(new Estimate(0, 1, 1), one(e, eat));
    }

    // ---- constructor validation -----------------------------------------------------------

    @Test
    void decayMustBeInZeroOneInclusiveOfOne() {
        for (double bad : new double[] {0.0, -0.1, 1.0001, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class,
                    () -> new OperantEstimator(() -> HUNGER, new double[] {50, 100}, bad), "decay " + bad);
        }
        new OperantEstimator(() -> HUNGER, new double[] {50, 100}, 1.0);
        new OperantEstimator(() -> HUNGER, new double[] {50, 100}, 0.5);
    }

    @Test
    void constructorRejectsBadArgumentsAndAcceptsNoEdges() {
        assertThrows(IllegalArgumentException.class, () -> new OperantEstimator(null));
        assertThrows(IllegalArgumentException.class, () -> new OperantEstimator(() -> HUNGER, null, 0.995));
        assertThrows(IllegalArgumentException.class,
                () -> new OperantEstimator(() -> HUNGER, new double[] {100, 50}, 0.995));
        assertThrows(IllegalArgumentException.class,
                () -> new OperantEstimator(() -> HUNGER, new double[] {50, 50}, 0.995));
        assertThrows(IllegalArgumentException.class,
                () -> new OperantEstimator(() -> HUNGER, new double[] {Double.NaN}, 0.995));

        OperantEstimator oneBin = new OperantEstimator(() -> HUNGER, new double[0], 1.0);
        Action eat = act(ActionType.EAT, FruitType.RED_APPLE, 10, 1);
        oneBin.record(HUNGER, eat, 1.0);
        assertTrue(one(oneBin, act(ActionType.EAT, FruitType.RED_APPLE, 500, 2)).q() > 0, "no edges: one bin");
    }

    @Test
    void recordAndEstimateRejectNullAction() {
        OperantEstimator e = withDrive(HUNGER);
        assertThrows(IllegalArgumentException.class, () -> e.record(HUNGER, null, 1.0));
        assertThrows(IllegalArgumentException.class, () -> e.estimate(java.util.Arrays.asList((Action) null), H));
        assertThrows(IllegalArgumentException.class, () -> e.estimate(null, H));
    }

    // ---- WelfordTable alone ----------------------------------------------------------------

    @Test
    void welfordTableKeysAreIndependentAndDecayMath() {
        WelfordTable<String> t = new WelfordTable<>(0.5);
        t.observe("a", 1);
        t.observe("a", 3);
        t.observe("b", 10);

        // Hand computation with d = 0.5: after the second observation n = 0.5 * 1 + 1 = 1.5,
        // mean = 1 + 2 / 1.5 = 7/3, M2 = 0.5 * 0 + 2 * (3 - 7/3) = 4/3, so sd = sqrt((4/3) / 0.5) = sqrt(8/3).
        WelfordTable.Stats a = t.stats("a");
        assertEquals(1.5, a.n(), TOL);
        assertEquals(7.0 / 3.0, a.mean(), TOL);
        assertEquals(Math.sqrt(8.0 / 3.0), a.sd(), TOL);

        WelfordTable.Stats b = t.stats("b");
        assertEquals(1.0, b.n(), TOL);
        assertEquals(10.0, b.mean(), TOL);
        assertTrue(Double.isNaN(b.sd()), "sd undefined for n <= 1");
    }

    @Test
    void welfordTableReadDoesNotInsertAndRejectsBadDecay() {
        WelfordTable<String> t = new WelfordTable<>(1.0);
        WelfordTable.Stats unseen = t.stats("missing");
        assertEquals(0.0, unseen.n(), 0.0);
        assertEquals(0.0, unseen.mean(), 0.0);
        assertEquals(0.0, t.stats("missing").n(), 0.0);
        assertThrows(IllegalArgumentException.class, () -> new WelfordTable<String>(0.0));
        assertThrows(IllegalArgumentException.class, () -> new WelfordTable<String>(1.5));
    }
}
