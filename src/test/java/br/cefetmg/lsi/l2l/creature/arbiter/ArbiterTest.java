package br.cefetmg.lsi.l2l.creature.arbiter;

import br.cefetmg.lsi.l2l.common.SequentialId;
import br.cefetmg.lsi.l2l.creature.arbiter.Arbiter.Controller;
import br.cefetmg.lsi.l2l.creature.arbiter.Arbiter.ControllerType;
import br.cefetmg.lsi.l2l.creature.arbiter.Arbiter.Params;
import br.cefetmg.lsi.l2l.creature.arbiter.ArbitrationDecision.ExplorationSource;
import br.cefetmg.lsi.l2l.creature.common.Action;
import br.cefetmg.lsi.l2l.creature.common.ActionType;
import br.cefetmg.lsi.l2l.creature.common.Perception;
import br.cefetmg.lsi.l2l.world.FruitType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The arbiter core against real {@link OperantEstimator}s trained through {@code record}. Where the
 * decisiveness is checked by hand, the cells hold one observation each: then Q = value / 2 and
 * SE = 1 / sqrt(2) for every candidate, so the expected numbers are closed-form.
 */
class ArbiterTest {

    private static final String HUNGER = "hunger";
    private static final double[] H = {0.0, 0.0, 0.0, 0.0};
    private static final double TOL = 1e-5;
    private static final Perception PERCEPTION = new Perception(FruitType.RED_APPLE, new SequentialId(1), 10, 0);

    private static Action on(ActionType type) {
        return new Action(type, PERCEPTION);
    }

    private static double[] repeat(double value, int times) {
        double[] out = new double[times];
        java.util.Arrays.fill(out, value);
        return out;
    }

    /** An estimator whose cells hold the given values; the decay is 1, so nothing is forgotten. */
    private static OperantEstimator trained(Map<ActionType, double[]> values) {
        OperantEstimator e = new OperantEstimator(new double[] {50, 100}, 1.0);
        values.forEach((type, vs) -> {
            for (double v : vs) {
                e.record(HUNGER, on(type), v);
            }
        });
        return e;
    }

    private static Controller operant(OperantEstimator e) {
        return new Controller(ControllerType.OPERANT, e);
    }

    // ---- 1. Decisiveness, hand-computed ---------------------------------------------------

    @Test
    void decisivenessIsPhiOfTheGapOverTheRootSumOfSquaredSes() {
        // Q = (1, 0), SE = (1, 1): Phi(1 / sqrt(2)) = 0.76025.
        assertEquals(0.76025, Arbiter.decisiveness(List.of(new Estimate(1, 1, 1), new Estimate(0, 1, 1))), 1e-5);
    }

    @Test
    void decisivenessUsesOnlyTheSesOfTheTwoHighestQ() {
        // The third candidate has the largest SE but the lowest Q, so it does not enter the formula.
        List<Estimate> ordered = List.of(new Estimate(1, 1, 1), new Estimate(0, 1, 1), new Estimate(-0.5, 5, 1));
        assertEquals(0.76025, Arbiter.decisiveness(ordered), 1e-5);
    }

    @Test
    void decisivenessDoesNotDependOnCandidateOrder() {
        List<Estimate> shuffled = List.of(new Estimate(-0.5, 5, 1), new Estimate(0, 1, 1), new Estimate(1, 1, 1));
        assertEquals(Arbiter.decisiveness(List.of(new Estimate(1, 1, 1), new Estimate(0, 1, 1), new Estimate(-0.5, 5, 1))),
                Arbiter.decisiveness(shuffled), 0.0);
    }

    @Test
    void equalQGivesOneHalf() {
        assertEquals(0.5, Arbiter.decisiveness(List.of(new Estimate(1, 1, 1), new Estimate(1, 2, 1))), 0.0);
    }

    @Test
    void oneCandidateGivesOne() {
        assertEquals(1.0, Arbiter.decisiveness(List.of(new Estimate(0.3, 1, 1))), 0.0);
    }

    @Test
    void zeroSesWithAGapGiveOne() {
        assertEquals(1.0, Arbiter.decisiveness(List.of(new Estimate(1, 0, 0), new Estimate(0, 0, 0))), 0.0);
    }

    @Test
    void decisivenessRejectsAnEmptyList() {
        assertThrows(IllegalArgumentException.class, () -> Arbiter.decisiveness(List.of()));
    }

    // ---- 2. Stopping rule, two real controllers --------------------------------------------

    @Test
    void aClearWinnerStopsTheConsultationAtTheFirstController() {
        OperantEstimator a = trained(Map.of(ActionType.EAT, repeat(2.0, 20), ActionType.AVOID, repeat(0.0, 20)));
        OperantEstimator b = new OperantEstimator(new double[] {50, 100}, 1.0);
        List<Action> cands = List.of(on(ActionType.EAT), on(ActionType.AVOID));

        ArbitrationDecision d = new Arbiter(List.of(operant(a), new Controller(ControllerType.EPISODIC, b)),
                Params.defaults(), new Random(1)).decide(cands, H, HUNGER);

        assertEquals(List.of(ControllerType.OPERANT), consulted(d));
        assertEquals(ControllerType.OPERANT, d.decidingController());
        assertTrue(d.consultations().get(0).conf() > 0.999, "a clear winner is near-certain");
    }

    @Test
    void anUntrainedFirstControllerDoesNotStopTheConsultation() {
        OperantEstimator a = new OperantEstimator(new double[] {50, 100}, 1.0);
        OperantEstimator b = trained(Map.of(ActionType.EAT, repeat(2.0, 20), ActionType.AVOID, repeat(0.0, 20)));
        List<Action> cands = List.of(on(ActionType.EAT), on(ActionType.AVOID));

        ArbitrationDecision d = new Arbiter(List.of(new Controller(ControllerType.EPISODIC, a), operant(b)),
                Params.defaults(), new Random(1)).decide(cands, H, HUNGER);

        assertEquals(List.of(ControllerType.EPISODIC, ControllerType.OPERANT), consulted(d));
        assertEquals(ControllerType.OPERANT, d.decidingController());
    }

    @Test
    void twoUntrainedControllersTieAndTheCheaperOneDecides() {
        OperantEstimator a = new OperantEstimator(new double[] {50, 100}, 1.0);
        OperantEstimator b = new OperantEstimator(new double[] {50, 100}, 1.0);
        List<Action> cands = List.of(on(ActionType.EAT), on(ActionType.AVOID));

        ArbitrationDecision d = new Arbiter(List.of(new Controller(ControllerType.EPISODIC, a), operant(b)),
                Params.defaults(), new Random(1)).decide(cands, H, HUNGER);

        assertEquals(List.of(ControllerType.EPISODIC, ControllerType.OPERANT), consulted(d));
        assertEquals(ControllerType.EPISODIC, d.decidingController(), "conf tie: strict > keeps the cheaper");
    }

    @Test
    void theThresholdIsInclusive() {
        // EAT observed once at 2.0: Q = 1, SE = 1/sqrt(2). AVOID is unseen: Q = 0, SE = 1.
        // conf = Phi(1 / sqrt(1/2 + 1)) = Phi(0.8165) = 0.7929, just below the default 0.8.
        OperantEstimator a = trained(Map.of(ActionType.EAT, new double[] {2.0}));
        OperantEstimator b = trained(Map.of(ActionType.EAT, new double[] {0.0}));
        List<Action> cands = List.of(on(ActionType.EAT), on(ActionType.AVOID));
        double conf = Arbiter.decisiveness(a.estimate(cands, H, HUNGER));
        assertTrue(conf < 0.8, "the fixture sits just under the default threshold: " + conf);

        ArbitrationDecision atTheThreshold = new Arbiter(
                List.of(operant(a), new Controller(ControllerType.EPISODIC, b)),
                new Params(conf, 0.03, 1.0), new Random(1)).decide(cands, H, HUNGER);
        assertEquals(List.of(ControllerType.OPERANT), consulted(atTheThreshold), "conf == theta stops (>=)");

        ArbitrationDecision belowIt = new Arbiter(
                List.of(operant(a), new Controller(ControllerType.EPISODIC, b)),
                Params.defaults(), new Random(1)).decide(cands, H, HUNGER);
        assertEquals(List.of(ControllerType.OPERANT, ControllerType.EPISODIC), consulted(belowIt),
                "conf below 0.8 keeps consulting");
    }

    // ---- 3. Most decisive when none reaches theta ------------------------------------------

    @Test
    void theMostDecisiveConsultedControllerDecidesWhenNoneReachesTheta() {
        // A: EAT Q = 0.25, AVOID Q = 0, SE = 1/sqrt(2): conf = Phi(0.25) = 0.5987.
        OperantEstimator a = trained(Map.of(ActionType.EAT, new double[] {0.5}, ActionType.AVOID, new double[] {0.0}));
        // B: EAT Q = 0.525, AVOID Q = 0: conf = Phi(0.525) = 0.7002.
        OperantEstimator b = trained(Map.of(ActionType.EAT, new double[] {1.05}, ActionType.AVOID, new double[] {0.0}));
        List<Action> cands = List.of(on(ActionType.EAT), on(ActionType.AVOID));

        ArbitrationDecision d = new Arbiter(List.of(operant(a), new Controller(ControllerType.EPISODIC, b)),
                Params.defaults(), new Random(1)).decide(cands, H, HUNGER);

        assertEquals(List.of(ControllerType.OPERANT, ControllerType.EPISODIC), consulted(d));
        assertEquals(0.5987, d.consultations().get(0).conf(), 1e-4);
        assertEquals(0.7002, d.consultations().get(1).conf(), 1e-4);
        assertEquals(ControllerType.EPISODIC, d.decidingController());

        List<Estimate> bEstimates = b.estimate(cands, H, HUNGER);
        assertEquals(bEstimates.get(d.chosenIndex()), d.chosenEstimate(), "the drawn estimates are B's");
        assertEquals(bEstimates.get(1), d.runnerUpEstimate(), "the runner-up is AVOID, the second-highest Q of B");
    }

    // ---- 4. Thompson draw, seeded and exact ------------------------------------------------

    private static final OperantEstimator THREE = trained(Map.of(
            ActionType.EAT, new double[] {1.0},
            ActionType.AVOID, new double[] {0.2}));
    private static final List<Action> THREE_CANDIDATES =
            List.of(on(ActionType.EAT), on(ActionType.AVOID), on(ActionType.APPROACH));

    /** The first seed whose first uniform is at or above {@code below} (or below it, when {@code below} is false). */
    private static long seedWhoseFirstUniformIs(boolean below, double threshold) {
        for (long s = 1; ; s++) {
            if ((new Random(s).nextDouble() < threshold) == below) {
                return s;
            }
        }
    }

    @Test
    void thompsonDrawMatchesTheHandComputedArgmaxForTheSeed() {
        long seed = seedWhoseFirstUniformIs(false, 0.03);
        List<Estimate> est = THREE.estimate(THREE_CANDIDATES, H, HUNGER);

        Random replay = new Random(seed);
        replay.nextDouble();
        int expected = 0;
        double best = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < est.size(); i++) {
            double draw = est.get(i).q() + 1.0 * est.get(i).se() * replay.nextGaussian();
            if (draw > best) {
                best = draw;
                expected = i;
            }
        }

        ArbitrationDecision d = new Arbiter(List.of(operant(THREE)), Params.defaults(), new Random(seed))
                .decide(THREE_CANDIDATES, H, HUNGER);
        assertEquals(expected, d.chosenIndex());
        assertEquals(expected == 0 ? ExplorationSource.GREEDY : ExplorationSource.THOMPSON, d.source());
    }

    @Test
    void theFloorPicksByNextIntWhenItFires() {
        long seed = seedWhoseFirstUniformIs(true, 0.03);
        Random replay = new Random(seed);
        replay.nextDouble();
        int expected = replay.nextInt(THREE_CANDIDATES.size());

        ArbitrationDecision d = new Arbiter(List.of(operant(THREE)), Params.defaults(), new Random(seed))
                .decide(THREE_CANDIDATES, H, HUNGER);
        assertEquals(ExplorationSource.EPSILON, d.source());
        assertEquals(expected, d.chosenIndex());
    }

    // ---- 5. Same seed, same sequence -------------------------------------------------------

    @Test
    void sameSeedGivesTheSameSequenceOfChoices() {
        List<Integer> first = choices(42, 1000);
        List<Integer> second = choices(42, 1000);
        assertEquals(first, second);
        assertTrue(first.stream().distinct().count() > 1, "the sequence is not degenerate");
    }

    private static List<Integer> choices(long seed, int n) {
        Arbiter arbiter = new Arbiter(List.of(operant(THREE)), Params.defaults(), new Random(seed));
        List<Integer> out = new java.util.ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            out.add(arbiter.decide(THREE_CANDIDATES, H, HUNGER).chosenIndex());
        }
        return out;
    }

    // ---- 6. Distribution -------------------------------------------------------------------

    private static final OperantEstimator TWO = trained(Map.of(
            ActionType.EAT, new double[] {1.0},
            ActionType.AVOID, new double[] {0.0}));
    private static final List<Action> TWO_CANDIDATES = List.of(on(ActionType.EAT), on(ActionType.AVOID));
    private static final int DRAWS = 20_000;

    @Test
    void firstCandidateShareMatchesPhiOfTheGapOverSeTimesRootTwo() {
        // Q = (0.5, 0), SE = (1/sqrt(2), 1/sqrt(2)): P(first) = Phi(0.5 / (1/sqrt(2) * sqrt(2))) = Phi(0.5).
        double p = 0.691462;
        int first = 0;
        Arbiter arbiter = new Arbiter(List.of(operant(TWO)), new Params(0.8, 0.0, 1.0), new Random(7));
        for (int i = 0; i < DRAWS; i++) {
            if (arbiter.decide(TWO_CANDIDATES, H, HUNGER).chosenIndex() == 0) {
                first++;
            }
        }
        double bound = 4 * Math.sqrt(p * (1 - p) / DRAWS);
        assertEquals(p, (double) first / DRAWS, bound);
    }

    @Test
    void tauZeroIsPureGreedy() {
        Arbiter arbiter = new Arbiter(List.of(operant(TWO)), new Params(0.8, 0.0, 0.0), new Random(7));
        for (int i = 0; i < 20; i++) {
            ArbitrationDecision d = arbiter.decide(TWO_CANDIDATES, H, HUNGER);
            assertEquals(0, d.chosenIndex());
            assertEquals(ExplorationSource.GREEDY, d.source());
        }
    }

    @Test
    void epsilonOneChoosesEachOfFourCandidatesAboutAQuarterOfTheTime() {
        List<Action> four = List.of(on(ActionType.EAT), on(ActionType.AVOID),
                on(ActionType.APPROACH), on(ActionType.OBSERVE));
        Arbiter arbiter = new Arbiter(List.of(operant(new OperantEstimator(new double[] {50, 100}, 1.0))),
                new Params(0.8, 1.0, 1.0), new Random(7));
        int[] counts = new int[4];
        for (int i = 0; i < DRAWS; i++) {
            ArbitrationDecision d = arbiter.decide(four, H, HUNGER);
            assertEquals(ExplorationSource.EPSILON, d.source());
            counts[d.chosenIndex()]++;
        }
        double p = 0.25;
        double bound = 4 * Math.sqrt(p * (1 - p) / DRAWS);
        for (int c : counts) {
            assertEquals(p, (double) c / DRAWS, bound);
        }
    }

    @Test
    void defaultEpsilonFiresAboutThreePercentOfTheTime() {
        Arbiter arbiter = new Arbiter(List.of(operant(TWO)), Params.defaults(), new Random(7));
        int floor = 0;
        for (int i = 0; i < DRAWS; i++) {
            if (arbiter.decide(TWO_CANDIDATES, H, HUNGER).source() == ExplorationSource.EPSILON) {
                floor++;
            }
        }
        double p = 0.03;
        assertEquals(p, (double) floor / DRAWS, 4 * Math.sqrt(p * (1 - p) / DRAWS));
    }

    // ---- 7. Single candidate ---------------------------------------------------------------

    @Test
    void aSingleCandidateIsReturnedWithoutConsumingRandomNumbers() {
        Random random = new Random(9);
        Arbiter arbiter = new Arbiter(List.of(operant(TWO)), Params.defaults(), random);
        List<Action> one = List.of(on(ActionType.EAT));

        ArbitrationDecision d = arbiter.decide(one, H, HUNGER);

        assertEquals(ExplorationSource.SINGLE, d.source());
        assertEquals(0, d.chosenIndex());
        assertNull(d.runnerUpEstimate());
        assertEquals(TWO.estimate(one, H, HUNGER).get(0), d.chosenEstimate(), "its Q is still known");
        assertEquals(new Random(9).nextDouble(), random.nextDouble(), 0.0, "the stream was not advanced");
    }

    // ---- 8. Validation ---------------------------------------------------------------------

    @Test
    void decideRejectsEmptyAndNullCandidates() {
        Arbiter arbiter = new Arbiter(List.of(operant(TWO)), Params.defaults(), new Random(1));
        assertThrows(IllegalArgumentException.class, () -> arbiter.decide(List.of(), H, HUNGER));
        assertThrows(IllegalArgumentException.class, () -> arbiter.decide(null, H, HUNGER));
        assertThrows(IllegalArgumentException.class, () -> arbiter.decide(java.util.Arrays.asList((Action) null), H, HUNGER));
    }

    @Test
    void paramsOutsideTheirRangesAreRejected() {
        assertEquals(new Params(0.8, 0.03, 1.0), Params.defaults());
        assertThrows(IllegalArgumentException.class, () -> new Params(0.49, 0.03, 1.0));
        assertThrows(IllegalArgumentException.class, () -> new Params(1.01, 0.03, 1.0));
        assertThrows(IllegalArgumentException.class, () -> new Params(Double.NaN, 0.03, 1.0));
        assertThrows(IllegalArgumentException.class, () -> new Params(0.8, -0.01, 1.0));
        assertThrows(IllegalArgumentException.class, () -> new Params(0.8, 1.01, 1.0));
        assertThrows(IllegalArgumentException.class, () -> new Params(0.8, 0.03, -1.0));
        assertThrows(IllegalArgumentException.class, () -> new Params(0.8, 0.03, Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> new Params(0.8, 0.03, Double.POSITIVE_INFINITY));
    }

    @Test
    void anArbiterNeedsAtLeastOneController() {
        assertThrows(IllegalArgumentException.class, () -> new Arbiter(List.of(), Params.defaults(), new Random(1)));
        assertThrows(IllegalArgumentException.class, () -> new Arbiter(null, Params.defaults(), new Random(1)));
    }

    private static List<ControllerType> consulted(ArbitrationDecision d) {
        return d.consultations().stream().map(ArbitrationDecision.Consultation::controller).toList();
    }
}
