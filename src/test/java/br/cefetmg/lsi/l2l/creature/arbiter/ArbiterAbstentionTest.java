package br.cefetmg.lsi.l2l.creature.arbiter;

import br.cefetmg.lsi.l2l.common.SequentialId;
import br.cefetmg.lsi.l2l.creature.arbiter.Arbiter.Controller;
import br.cefetmg.lsi.l2l.creature.arbiter.Arbiter.ControllerType;
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

/**
 * Abstention and the whole-draw stop rule (docs/plans/operant-kalman-and-stop-rule.md, section 2). All deterministic: every spread is
 * zero where a pick must be certain, and the confidence is compared with a closed form, never with a lucky seed.
 */
class ArbiterAbstentionTest {

    private static final Arbiter.Params NO_FLOOR = new Arbiter.Params(0.8, 0.0, 1.0, 0.1);
    private static final double[] H = {0, 0, 0, 0};

    private static List<Action> candidates(int n) {
        List<Action> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(new Action(ActionType.EAT, new Perception(FruitType.RED_APPLE, new SequentialId(i + 1), 10, 0)));
        }
        return out;
    }

    private static Controller fixed(ControllerType type, Estimate... estimates) {
        return new Controller(type, (c, h, d) -> List.of(estimates));
    }

    private static Arbiter arbiter(Arbiter.StopRule rule, Controller... controllers) {
        return new Arbiter(List.of(controllers), NO_FLOOR, new Random(7), Arbiter.Mode.ARBITRATION, rule);
    }

    @Test
    void aControllerWithNoOpinionOnAnyCandidateIsNeverDecisiveAndPicksUniformlyWhenAlone() {
        Estimate a = Estimate.abstain();
        Arbiter arb = arbiter(Arbiter.StopRule.TOP_TWO, fixed(ControllerType.OPERANT, a, a, a, a));
        ArbitrationDecision d = arb.decide(candidates(4), H, null);
        assertEquals(0.0, d.consultations().get(0).conf(), 0.0);
        assertEquals(ArbitrationDecision.ExplorationSource.ABSTAIN_UNIFORM, d.source());
        assertTrue(d.bestEstimate().abstains());
        assertEquals(4, d.nTiedAtMax());
    }

    @Test
    void whenTheOperantAbstainsOnEverythingTheDearerControllerDecides() {
        Estimate a = Estimate.abstain();
        Arbiter arb = arbiter(Arbiter.StopRule.TOP_TWO, fixed(ControllerType.OPERANT, a, a),
                fixed(ControllerType.EPISODIC, new Estimate(0.1, 0.0, 0.0, 5), new Estimate(0.4, 0.0, 0.0, 5)));
        ArbitrationDecision d = arb.decide(candidates(2), H, null);
        assertEquals(ControllerType.EPISODIC, d.decidingController());
        assertEquals(1, d.chosenIndex());
        assertEquals(2, d.consultations().size());
    }

    @Test
    void anAbstainerNeverGetsADrawOfItsOwnSoACertainPositiveOpinionAlwaysWins() {
        Estimate good = new Estimate(0.5, 0.0, 0.0);
        Estimate a = Estimate.abstain();
        Arbiter arb = arbiter(Arbiter.StopRule.TOP_TWO, fixed(ControllerType.OPERANT, a, good, a, a));
        for (int i = 0; i < 50; i++) {
            ArbitrationDecision d = arb.decide(candidates(4), H, null);
            assertEquals(1, d.chosenIndex());
            assertEquals(ArbitrationDecision.ExplorationSource.GREEDY, d.source());
            assertEquals(1, d.nTiedAtMax());
        }
    }

    @Test
    void aCertainNegativeOpinionLosesToTheNothingClaimedPseudoCandidateAndAnAbstainedOneIsPicked() {
        Estimate bad = new Estimate(-0.5, 0.0, 0.0);
        Estimate a = Estimate.abstain();
        Arbiter arb = arbiter(Arbiter.StopRule.TOP_TWO, fixed(ControllerType.OPERANT, a, bad, a, a));
        for (int i = 0; i < 50; i++) {
            ArbitrationDecision d = arb.decide(candidates(4), H, null);
            assertEquals(ArbitrationDecision.ExplorationSource.ABSTAIN_UNIFORM, d.source());
            assertTrue(d.chosenIndex() == 0 || d.chosenIndex() == 2 || d.chosenIndex() == 3, "never the known-bad one");
            assertTrue(d.chosenEstimate().abstains());
            assertEquals(-0.5, d.bestEstimate().q(), 0.0, "the best estimate is the best opinion, not the pseudo-candidate");
            assertEquals(null, d.runnerUpEstimate(), "one opinion only");
        }
    }

    @Test
    void wholeDrawDecisivenessOfTwoCandidatesIsTheTwoBestFormula() {
        // P(draw1 > draw2) = Phi((q1 - q2) / sqrt(se1^2 + se2^2)); here Phi(1 / sqrt(2)) = 0.76025.
        Arbiter arb = arbiter(Arbiter.StopRule.WHOLE_DRAW, fixed(ControllerType.OPERANT, new Estimate(1, 1, 1), new Estimate(0, 1, 1)));
        assertEquals(0.76025, arb.decide(candidates(2), H, null).consultations().get(0).conf(), 1e-4);
    }

    @Test
    void wholeDrawDecisivenessOfThreeEqualCandidatesIsOneThirdNotTheTopTwoHalf() {
        Estimate e = new Estimate(0.2, 0.5, 0.5);
        Arbiter whole = arbiter(Arbiter.StopRule.WHOLE_DRAW, fixed(ControllerType.OPERANT, e, e, e));
        Arbiter top2 = arbiter(Arbiter.StopRule.TOP_TWO, fixed(ControllerType.OPERANT, e, e, e));
        assertEquals(1.0 / 3.0, whole.decide(candidates(3), H, null).consultations().get(0).conf(), 1e-4);
        assertEquals(0.5, top2.decide(candidates(3), H, null).consultations().get(0).conf(), 0.0);
    }

    @Test
    void wholeDrawDecisivenessFallsAsCandidatesAreAdded() {
        double prev = 1.0;
        for (int n = 2; n <= 8; n++) {
            Estimate[] est = new Estimate[n];
            est[0] = new Estimate(0.3, 0.2, 0.2);
            for (int i = 1; i < n; i++) {
                est[i] = new Estimate(0.0, 0.2, 0.2);
            }
            double conf = arbiter(Arbiter.StopRule.WHOLE_DRAW, fixed(ControllerType.OPERANT, est))
                    .decide(candidates(n), H, null).consultations().get(0).conf();
            assertTrue(conf < prev, "n=" + n + ": " + conf + " should be below " + prev);
            prev = conf;
        }
    }

    @Test
    void wholeDrawWithZeroSpreadIsAStepFunction() {
        Arbiter arb = arbiter(Arbiter.StopRule.WHOLE_DRAW, fixed(ControllerType.OPERANT, new Estimate(1, 0, 0), new Estimate(0, 0, 0)));
        assertEquals(1.0, arb.decide(candidates(2), H, null).consultations().get(0).conf(), 0.0);
    }

    @Test
    void wholeDrawIncludesThePseudoCandidateInTheConfidence() {
        // One opinion (q 0.1, se 0.1) and two abstainers: the pseudo-candidate is q 0 with the same se, so the opinion wins with Phi(0.1 / (0.1 sqrt 2)).
        Estimate a = Estimate.abstain();
        Arbiter arb = arbiter(Arbiter.StopRule.WHOLE_DRAW, fixed(ControllerType.OPERANT, new Estimate(0.1, 0.1, 0.1), a, a));
        assertEquals(0.76025, arb.decide(candidates(3), H, null).consultations().get(0).conf(), 1e-4);
    }

    @Test
    void fixedOrderWithAnOperantThatAbstainsOnEverythingFallsToTheUniformPickWhenEpisodicLacksEvidence() {
        Estimate a = Estimate.abstain();
        Arbiter arb = new Arbiter(List.of(fixed(ControllerType.OPERANT, a, a),
                fixed(ControllerType.EPISODIC, new Estimate(0.1, 0.0, 0.0, 1.0), new Estimate(0.4, 0.0, 0.0, 1.0))),
                NO_FLOOR, new Random(3), Arbiter.Mode.FIXED_ORDER, Arbiter.StopRule.TOP_TWO);
        ArbitrationDecision d = arb.decide(candidates(2), H, null, 1.0);
        assertEquals(ControllerType.OPERANT, d.decidingController());
        assertEquals(ArbitrationDecision.ExplorationSource.ABSTAIN_UNIFORM, d.source());
    }

    @Test
    void fixedOrderLetsEpisodicDecideWhenItHasEvidence() {
        Estimate a = Estimate.abstain();
        Arbiter arb = new Arbiter(List.of(fixed(ControllerType.OPERANT, a, a),
                fixed(ControllerType.EPISODIC, new Estimate(0.1, 0.0, 0.0, 5.0), new Estimate(0.4, 0.0, 0.0, 5.0))),
                NO_FLOOR, new Random(3), Arbiter.Mode.FIXED_ORDER, Arbiter.StopRule.TOP_TWO);
        assertEquals(ControllerType.EPISODIC, arb.decide(candidates(2), H, null, 1.0).decidingController());
    }

    @Test
    void theStreamIsUnchangedWhenNothingAbstains() {
        // Same seed, same estimates: the abstention code consumes the same numbers as before (one double, then one gaussian per candidate).
        Estimate[] est = {new Estimate(0.1, 0.2, 0.2), new Estimate(0.2, 0.2, 0.2), new Estimate(0.0, 0.2, 0.2)};
        Arbiter a1 = arbiter(Arbiter.StopRule.TOP_TWO, fixed(ControllerType.OPERANT, est));
        Random reference = new Random(7);
        for (int i = 0; i < 20; i++) {
            ArbitrationDecision d = a1.decide(candidates(3), H, null);
            reference.nextDouble();
            int w = 0;
            double best = Double.NEGATIVE_INFINITY;
            for (int j = 0; j < 3; j++) {
                double draw = est[j].q() + 1.0 * est[j].se() * reference.nextGaussian();
                if (draw > best) {
                    best = draw;
                    w = j;
                }
            }
            assertEquals(w, d.chosenIndex());
        }
    }
}
