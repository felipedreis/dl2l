package br.cefetmg.lsi.l2l.creature.arbiter;

import br.cefetmg.lsi.l2l.common.SequentialId;
import br.cefetmg.lsi.l2l.creature.common.Action;
import br.cefetmg.lsi.l2l.creature.common.ActionType;
import br.cefetmg.lsi.l2l.creature.common.Perception;
import br.cefetmg.lsi.l2l.world.FruitType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests of the efficiency budget and novelty bonus (#114) and the fixed-order mode (#116) of {@link Arbiter}. */
class ArbiterBudgetAndModeTest {

    private static final double[] H = {3, 0.18, 0.18, 0.18};
    private static final Action A = new Action(ActionType.APPROACH,
            new Perception(FruitType.RED_APPLE, new SequentialId(1), 50, 0));
    private static final Action B = new Action(ActionType.WANDER,
            new Perception(FruitType.RED_APPLE, new SequentialId(1), 50, 0));
    private static final List<Action> CANDS = List.of(A, B);

    /** A stub controller with fixed estimates that counts how often it is asked. */
    private static final class Stub implements ValueEstimator {
        final List<Estimate> estimates;
        int calls;

        Stub(Estimate a, Estimate b) {
            this.estimates = List.of(a, b);
        }

        @Override
        public List<Estimate> estimate(List<Action> candidates, double[] h, String focalDrive) {
            calls++;
            return estimates.subList(0, candidates.size());
        }
    }

    private static Estimate e(double q, double se) {
        return new Estimate(q, se, 1.0);
    }

    private static Estimate ep(double q, double se, double nEff) {
        return new Estimate(q, se, 1.0, nEff);
    }

    private static Arbiter arbiter(Stub operant, Stub episodic, Arbiter.Mode mode, Arbiter.Params params, long seed) {
        return new Arbiter(List.of(new Arbiter.Controller(Arbiter.ControllerType.OPERANT, operant),
                new Arbiter.Controller(Arbiter.ControllerType.EPISODIC, episodic)), params, new Random(seed), mode);
    }

    private static Arbiter.Params greedy() {
        return new Arbiter.Params(0.8, 0.0, 0.0);
    }

    // ---- #114: budget ----------------------------------------------------------------------

    @Test
    void episodicIsNotConsultedBelowItsMinimumEfficiencyAndIsAtTheBoundary() {
        Stub operant = new Stub(e(0, 1), e(0, 1)); // tied: never decisive
        Stub episodic = new Stub(e(1, 0.1), e(0, 0.1));
        Arbiter arb = arbiter(operant, episodic, Arbiter.Mode.ARBITRATION, greedy(), 1);

        ArbitrationDecision poor = arb.decide(CANDS, H, "hunger", 0.29);
        assertEquals(0, episodic.calls);
        assertEquals(1, poor.consultations().size());
        assertEquals(Arbiter.ControllerType.OPERANT, poor.mostExpensiveAffordable());
        assertEquals(Arbiter.ControllerType.OPERANT, poor.decidingController());

        ArbitrationDecision exact = arb.decide(CANDS, H, "hunger", 0.3);
        assertEquals(1, episodic.calls);
        assertEquals(Arbiter.ControllerType.EPISODIC, exact.mostExpensiveAffordable());
        assertEquals(Arbiter.ControllerType.EPISODIC, exact.decidingController());
        assertEquals(A, exact.chosen());
    }

    @Test
    void theThreeArgumentDecideTreatsEveryControllerAsAffordable() {
        Stub operant = new Stub(e(0, 1), e(0, 1));
        Stub episodic = new Stub(e(1, 0.1), e(0, 0.1));
        ArbitrationDecision d = arbiter(operant, episodic, Arbiter.Mode.ARBITRATION, greedy(), 1).decide(CANDS, H, "hunger");
        assertEquals(Arbiter.ControllerType.EPISODIC, d.decidingController());
    }

    @Test
    void theCheapestControllerIsAlwaysConsultedEvenAtNaNOrZeroEfficiency() {
        Stub operant = new Stub(e(1, 0.1), e(0, 0.1));
        Stub episodic = new Stub(e(0, 1), e(0, 1));
        Arbiter arb = arbiter(operant, episodic, Arbiter.Mode.ARBITRATION, greedy(), 1);
        assertEquals(1, arb.decide(CANDS, H, null, Double.NaN).consultations().size());
        assertEquals(1, arb.decide(CANDS, H, null, 0.0).consultations().size());
        assertEquals(0, episodic.calls);
    }

    @Test
    void aDecisiveCheapControllerStillStopsTheConsultationWhenTheBudgetAllowsMore() {
        Stub operant = new Stub(e(1, 0.01), e(-1, 0.01));
        Stub episodic = new Stub(e(0, 1), e(0, 1));
        ArbitrationDecision d = arbiter(operant, episodic, Arbiter.Mode.ARBITRATION, greedy(), 1).decide(CANDS, H, null, 1.0);
        assertEquals(1, d.consultations().size());
        assertEquals(0, episodic.calls);
        assertEquals(Arbiter.ControllerType.EPISODIC, d.mostExpensiveAffordable(), "affordable is not consulted");
    }

    @Test
    void plannerNeedsPointSevenOfEfficiency() {
        Stub op = new Stub(e(0, 1), e(0, 1));
        Stub epi = new Stub(e(0, 1), e(0, 1));
        Stub plan = new Stub(e(1, 0.1), e(0, 0.1));
        Arbiter arb = new Arbiter(List.of(new Arbiter.Controller(Arbiter.ControllerType.OPERANT, op),
                new Arbiter.Controller(Arbiter.ControllerType.EPISODIC, epi),
                new Arbiter.Controller(Arbiter.ControllerType.PLANNER, plan)), greedy(), new Random(1));
        assertEquals(Arbiter.ControllerType.EPISODIC, arb.decide(CANDS, H, null, 0.69).mostExpensiveAffordable());
        assertEquals(0, plan.calls);
        assertEquals(Arbiter.ControllerType.PLANNER, arb.decide(CANDS, H, null, 0.7).mostExpensiveAffordable());
        assertEquals(1, plan.calls);
    }

    // ---- #114: novelty ---------------------------------------------------------------------

    @Test
    void noveltyCanOutweighASmallGapInFavourOfTheCandidateWithFewerNeighbours() {
        Stub operant = new Stub(e(0, 1), e(0, 1));
        // A is better by 0.01 but well known (bonus 0.1/sqrt(100)); B is unknown (bonus 0.1): the draw is exact (SE 0)
        Stub episodic = new Stub(ep(0.50, 0, 99), ep(0.49, 0, 0));
        ArbitrationDecision d = arbiter(operant, episodic, Arbiter.Mode.ARBITRATION, greedy(), 1).decide(CANDS, H, null, 1.0);
        assertEquals(Arbiter.ControllerType.EPISODIC, d.decidingController());
        assertEquals(B, d.chosen());
        assertEquals(0.1, d.noveltyBonus(), 1e-12);
        // without the bonus the better candidate wins
        ArbitrationDecision plain = new Arbiter(List.of(new Arbiter.Controller(Arbiter.ControllerType.OPERANT, operant),
                new Arbiter.Controller(Arbiter.ControllerType.EPISODIC, episodic)),
                new Arbiter.Params(0.8, 0.0, 0.0, 0.0), new Random(1)).decide(CANDS, H, null, 1.0);
        assertEquals(A, plain.chosen());
    }

    @Test
    void noveltyIsOneOverSqrtOnePlusNEffAndZeroWithoutANeighbourCount() {
        Stub operant = new Stub(e(0, 1), e(0, 1));
        Stub episodic = new Stub(ep(0.5, 0, 8), ep(0.4, 0, 8));
        ArbitrationDecision d = arbiter(operant, episodic, Arbiter.Mode.ARBITRATION, greedy(), 1).decide(CANDS, H, null, 1.0);
        assertEquals(0.1 / 3.0, d.noveltyBonus(), 1e-12);
        // the operant table has no n_eff: no bonus
        ArbitrationDecision op = arbiter(operant, episodic, Arbiter.Mode.ARBITRATION, greedy(), 1).decide(CANDS, H, null, 0.0);
        assertEquals(0.0, op.noveltyBonus(), 0.0);
    }

    @Test
    void noveltyDoesNotChangeHowManyRandomNumbersAreConsumed() {
        Stub operant = new Stub(e(0, 1), e(0, 1));
        Stub episodic = new Stub(ep(0.5, 0.3, 0), ep(0.4, 0.3, 50));
        Random withNovelty = new Random(7);
        Random without = new Random(7);
        Arbiter a1 = new Arbiter(List.of(new Arbiter.Controller(Arbiter.ControllerType.OPERANT, operant),
                new Arbiter.Controller(Arbiter.ControllerType.EPISODIC, episodic)),
                new Arbiter.Params(0.8, 0.03, 1.0, 0.1), withNovelty);
        Arbiter a2 = new Arbiter(List.of(new Arbiter.Controller(Arbiter.ControllerType.OPERANT, operant),
                new Arbiter.Controller(Arbiter.ControllerType.EPISODIC, episodic)),
                new Arbiter.Params(0.8, 0.03, 1.0, 0.0), without);
        for (int i = 0; i < 100; i++) {
            a1.decide(CANDS, H, null, 1.0);
            a2.decide(CANDS, H, null, 1.0);
        }
        assertEquals(without.nextLong(), withNovelty.nextLong());
    }

    // ---- #116: fixed order -----------------------------------------------------------------

    @Test
    void fixedOrderLetsEpisodicDecideWhenItHasEvidenceEvenIfOperantIsDecisive() {
        Stub operant = new Stub(e(1, 0.01), e(-1, 0.01));                // decisive for A
        Stub episodic = new Stub(ep(0, 0.5, 10), ep(0.2, 0.5, 10));       // evidence, prefers B
        ArbitrationDecision arbitrated = arbiter(operant, episodic, Arbiter.Mode.ARBITRATION, greedy(), 1).decide(CANDS, H, null, 1.0);
        ArbitrationDecision fixed = arbiter(operant, episodic, Arbiter.Mode.FIXED_ORDER, greedy(), 1).decide(CANDS, H, null, 1.0);
        assertEquals(Arbiter.ControllerType.OPERANT, arbitrated.decidingController());
        assertEquals(A, arbitrated.chosen());
        assertEquals(Arbiter.ControllerType.EPISODIC, fixed.decidingController());
        assertEquals(B, fixed.chosen());
        assertEquals(2, fixed.consultations().size(), "every affordable controller is consulted");
    }

    @Test
    void fixedOrderFallsBackToOperantWithoutEvidenceOrBudget() {
        Stub operant = new Stub(e(1, 0.01), e(-1, 0.01));
        Stub sparse = new Stub(ep(0, 0.5, 10), ep(0.2, 0.5, 2.9));        // one candidate under 3 neighbours
        assertEquals(Arbiter.ControllerType.OPERANT,
                arbiter(operant, sparse, Arbiter.Mode.FIXED_ORDER, greedy(), 1).decide(CANDS, H, null, 1.0).decidingController());
        Stub rich = new Stub(ep(0, 0.5, 10), ep(0.2, 0.5, 3.0));          // exactly the minimum
        assertEquals(Arbiter.ControllerType.EPISODIC,
                arbiter(operant, rich, Arbiter.Mode.FIXED_ORDER, greedy(), 1).decide(CANDS, H, null, 1.0).decidingController());
        assertEquals(Arbiter.ControllerType.OPERANT,
                arbiter(operant, rich, Arbiter.Mode.FIXED_ORDER, greedy(), 1).decide(CANDS, H, null, 0.2).decidingController(),
                "unaffordable episodic never decides, evidence or not");
    }

    @Test
    void theModesDifferOnlyInWhichControllerDecides() {
        // Same stubs, same seed, 300 decisions: the random stream ends in the same place, and wherever both modes
        // pick the same deciding controller everything about the decision is identical.
        Stub operant = new Stub(e(0.3, 0.2), e(0.0, 0.2));
        Stub episodic = new Stub(ep(0.1, 0.4, 12), ep(0.4, 0.4, 12));
        Random r1 = new Random(11), r2 = new Random(11);
        Arbiter arb = new Arbiter(List.of(new Arbiter.Controller(Arbiter.ControllerType.OPERANT, operant),
                new Arbiter.Controller(Arbiter.ControllerType.EPISODIC, episodic)), Arbiter.Params.defaults(), r1,
                Arbiter.Mode.ARBITRATION);
        Arbiter fix = new Arbiter(List.of(new Arbiter.Controller(Arbiter.ControllerType.OPERANT, operant),
                new Arbiter.Controller(Arbiter.ControllerType.EPISODIC, episodic)), Arbiter.Params.defaults(), r2,
                Arbiter.Mode.FIXED_ORDER);
        int same = 0, differ = 0;
        List<Boolean> deciderDiffers = new ArrayList<>();
        for (int i = 0; i < 300; i++) {
            ArbitrationDecision a = arb.decide(CANDS, H, null, 1.0);
            ArbitrationDecision f = fix.decide(CANDS, H, null, 1.0);
            assertEquals(Arbiter.ControllerType.EPISODIC, f.decidingController());
            boolean sameController = a.decidingController() == f.decidingController();
            deciderDiffers.add(!sameController);
            if (sameController) {
                same++;
                assertEquals(a.chosen(), f.chosen());
                assertEquals(a.source(), f.source());
                assertEquals(a.chosenEstimate(), f.chosenEstimate());
            } else {
                differ++;
                assertEquals(Arbiter.ControllerType.OPERANT, a.decidingController());
            }
        }
        assertEquals(r1.nextLong(), r2.nextLong(), "same random consumption in both modes");
        assertTrue(same + differ == 300);
    }

    @Test
    void aSingleCandidateStillDrawsNoRandomNumberInEitherMode() {
        Stub operant = new Stub(e(0, 1), e(0, 1));
        Stub episodic = new Stub(ep(0, 1, 10), ep(0, 1, 10));
        for (Arbiter.Mode mode : Arbiter.Mode.values()) {
            Random r = new Random(5);
            Arbiter arb = new Arbiter(List.of(new Arbiter.Controller(Arbiter.ControllerType.OPERANT, operant),
                    new Arbiter.Controller(Arbiter.ControllerType.EPISODIC, episodic)), Arbiter.Params.defaults(), r, mode);
            arb.decide(List.of(A), H, null, 1.0);
            assertEquals(new Random(5).nextLong(), r.nextLong(), mode.name());
        }
    }
}
