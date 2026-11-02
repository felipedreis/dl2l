package br.cefetmg.lsi.l2l.creature.testing;

import br.cefetmg.lsi.l2l.cluster.settings.LearningSettings;
import br.cefetmg.lsi.l2l.common.Constants;
import br.cefetmg.lsi.l2l.common.Point;
import br.cefetmg.lsi.l2l.common.SequentialId;
import br.cefetmg.lsi.l2l.creature.bd.ActionSelectionType;
import br.cefetmg.lsi.l2l.creature.bd.ArbitrationDecisionState;
import br.cefetmg.lsi.l2l.creature.bd.ChosenActionState;
import br.cefetmg.lsi.l2l.creature.bd.EpisodeReturnState;
import br.cefetmg.lsi.l2l.creature.common.Action;
import br.cefetmg.lsi.l2l.creature.common.ActionType;
import br.cefetmg.lsi.l2l.creature.common.Perception;
import br.cefetmg.lsi.l2l.creature.conditioning.expectancy.ExpectancyMode;
import br.cefetmg.lsi.l2l.stimuli.CorticalStimulus;
import br.cefetmg.lsi.l2l.stimuli.LuminousStimulus;
import br.cefetmg.lsi.l2l.stimuli.MuscularStimulus;
import br.cefetmg.lsi.l2l.world.FruitType;
import br.cefetmg.lsi.l2l.world.Self;
import org.junit.jupiter.api.Test;

import java.util.List;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Issue #104 end to end through the in-process harness: a real {@code FullAppraisal} with the arbiter
 * selector, the real operant table behind the harness, the real episode buffer and valuation.
 */
class ArbitrationFunctionalTest {

    private static final Point CREATURE_AT = new Point(100, 100);
    private static final Point APPLE_AT = new Point(150, 100);     // 50 units east of the creature
    private static final Point BOUNDS = new Point(1000, 1000);
    private static final long SEED = 104L;

    /** A2 as the check spec runs it: focal drive, operant estimator, arbiter over TARGET_DISTANCE only. */
    private static LearningSettings a2() {
        return new LearningSettings(false, false, List.of(ActionSelectionType.TARGET_DISTANCE),
                false, ExpectancyMode.DISCRETE, false, true, false, false, true, true, true,
                LearningSettings.ActionSelectorMode.ARBITER);
    }

    /** A0 with focal drive off: the legacy chain of the p84 baseline. Used by the flag-off parity test. */
    private static LearningSettings a0() {
        return new LearningSettings(false, false,
                List.of(ActionSelectionType.TARGET_DISTANCE, ActionSelectionType.AFFORDANCE, ActionSelectionType.RANDOM),
                false, ExpectancyMode.DISCRETE, false, true, false, false, true, false);
    }

    private static TestingHarness harness(LearningSettings settings) {
        return TestingHarness.builder().learningSettings(settings).position(CREATURE_AT)
                .worldBoundaries(BOUNDS).seed(SEED).build();
    }

    private static void seeApple(TestingHarness h) {
        h.injectLuminous(new LuminousStimulus(new SequentialId(42L), new SequentialId(43L), FruitType.RED_APPLE, APPLE_AT));
    }

    /** Ten observations of APPROACH at value 2.0 in every distance bin, under one focal drive. */
    private static void trainApproachOnRedApple(TestingHarness h, String drive) {
        for (double d : new double[] {10, 75, 120}) {
            Action approach = new Action(ActionType.APPROACH, new Perception(FruitType.RED_APPLE, new SequentialId(1), d, 0));
            for (int i = 0; i < 10; i++) {
                h.creature().operantStore().record(drive, approach, 2.0);
            }
        }
    }

    private static Action selfWander() {
        return new Action(ActionType.WANDER, new Perception(Self.get(), new SequentialId(7), 0, 0));
    }

    private static double storeQ(TestingHarness h, Action action, String drive) {
        return h.creature().operantStore().estimate(List.of(action), new double[4], drive).get(0).q();
    }

    private static List<ChosenActionState> chosen(TestingHarness h) {
        return h.bdSink().ofType(ChosenActionState.class);
    }

    // ---- 13. Selection and label ------------------------------------------------------------

    @Test
    void aHungryCreatureChoosesAnActionOfTheAppleAndTheDecisionIsLabelledArbiter() {
        TestingHarness h = harness(a2());
        h.creature().emotions().regulate(Constants.HUNGER, 3.0);
        seeApple(h);
        h.tick();

        List<ChosenActionState> rows = chosen(h);
        assertFalse(rows.isEmpty(), "the decision was persisted");
        assertTrue(rows.stream().allMatch(r -> r.getActionSelectionType() == ActionSelectionType.ARBITER));

        CorticalStimulus cortical = h.effectorCortexRecorder().lastOf(CorticalStimulus.class);
        assertNotNull(cortical, "the effector received a motor command");
        assertTrue(Set.of(ActionType.APPROACH, ActionType.AVOID, ActionType.SLEEP, ActionType.OBSERVE,
                        ActionType.WANDER, ActionType.EAT).contains(cortical.action));
        assertTrue(h.bodyRecorder().hasAny(MuscularStimulus.class));
    }

    // ---- 14. A learned habit steers the choice -----------------------------------------------

    @Test
    void aLearnedHabitSteersTheChoiceOverTwoHundredSeededCycles() {
        TestingHarness h = harness(a2());
        h.creature().emotions().regulate(Constants.HUNGER, 3.0);
        trainApproachOnRedApple(h, Constants.HUNGER);

        int cycles = 200;
        for (int i = 0; i < cycles; i++) {
            h.creature().setPosition(CREATURE_AT);   // a fixed geometry, so every cycle has the same candidates
            seeApple(h);
            h.tick();
        }

        List<ChosenActionState> rows = chosen(h);
        long approach = rows.stream().filter(r -> r.getAction() == ActionType.APPROACH).count();
        double share = (double) approach / rows.size();
        // Expected about 1 - epsilon/2 with two candidates (APPROACH and WANDER, the tendency's pair).
        assertTrue(share >= 0.9, "APPROACH share " + share + " over " + rows.size() + " decisions");
    }

    // ---- 15. chosenQ reaches the episode buffer ---------------------------------------------

    @Test
    void aPreEmptedEpisodeBootstrapsFromTheChosenQOfTheCutDecision() {
        TestingHarness h = harness(a2());
        h.creature().emotions().regulate(Constants.HUNGER, 3.0);
        trainApproachOnRedApple(h, Constants.HUNGER);
        // The cut decision is made under the new focus (pain), so its cell is the pain cell.
        trainApproachOnRedApple(h, Constants.PAIN);
        double approachUnderPain = storeQ(h, new Action(ActionType.APPROACH,
                new Perception(FruitType.RED_APPLE, new SequentialId(1), 75, 0)), Constants.PAIN);
        assertTrue(approachUnderPain > 1.0, "the APPROACH cell under pain is trained");
        for (int i = 0; i < 5; i++) {
            seeApple(h);
            h.tick();
        }
        // Pain past the critical threshold pre-empts the hunger episode.
        h.creature().emotions().regulate(Constants.PAIN, 6.0);
        for (int i = 0; i < 10; i++) {
            seeApple(h);
            h.tick();
        }

        List<EpisodeReturnState> preEmpted = h.bdSink().ofType(EpisodeReturnState.class).stream()
                .filter(r -> "PREEMPTED".equals(r.getCloseType())).toList();
        assertFalse(preEmpted.isEmpty(), "the pain challenger pre-empted the hunger episode");
        for (EpisodeReturnState r : preEmpted) {
            // The cut decision was APPROACH (its pain cell is trained) or WANDER (untrained under pain: zero).
            assertTrue(Math.abs(r.getBootstrap() - approachUnderPain) < 1e-9 || r.getBootstrap() == 0.0,
                    "bootstrap " + r.getBootstrap() + " is neither the trained APPROACH Q under pain " + approachUnderPain
                            + " nor the untrained WANDER zero");
        }
        // Under pain the tendency prior leaves AVOID and WANDER, both untrained there, so zero is the expected
        // bootstrap here. The non-zero case (a trained cut cell) is pinned exactly by the single-candidate test.
    }

    // ---- 16. Single candidate ---------------------------------------------------------------

    @Test
    void aSingleCandidateIsLabelledArbiterAndItsQBootstrapsTheCut() {
        // No apple in view: the self perception gives SLEEP and WANDER, and hunger's tendency leaves WANDER alone.
        TestingHarness h = harness(a2());
        h.creature().emotions().regulate(Constants.HUNGER, 3.0);
        for (int i = 0; i < 10; i++) {
            h.creature().operantStore().record(Constants.HUNGER, selfWander(), 2.0);
            h.creature().operantStore().record(Constants.PAIN, selfWander(), 2.0);
        }
        double wanderUnderPain = storeQ(h, selfWander(), Constants.PAIN);
        assertTrue(wanderUnderPain > 1.0, "the self cell under pain is trained");
        for (int i = 0; i < 5; i++) {
            h.tick();
        }
        h.creature().emotions().regulate(Constants.PAIN, 6.0);
        for (int i = 0; i < 10; i++) {
            h.tick();
        }

        List<ChosenActionState> rows = chosen(h);
        assertFalse(rows.isEmpty());
        assertTrue(rows.stream().allMatch(r -> r.getActionSelectionType() == ActionSelectionType.ARBITER));
        assertTrue(rows.stream().limit(5).allMatch(r -> r.getAction() == ActionType.WANDER),
                "the lone candidate is WANDER");

        List<EpisodeReturnState> preEmpted = h.bdSink().ofType(EpisodeReturnState.class).stream()
                .filter(r -> "PREEMPTED".equals(r.getCloseType())).toList();
        assertFalse(preEmpted.isEmpty());
        for (EpisodeReturnState r : preEmpted) {
            assertEquals(wanderUnderPain, r.getBootstrap(), 1e-9,
                    "the cut decision was the lone WANDER under pain: its Q, not zero, is the bootstrap");
        }
    }

    // ---- 17. Issue #105: one arbitration row per cycle, next to its chosen-action row --------

    private static List<ArbitrationDecisionState> arbitrationRows(TestingHarness h) {
        return h.bdSink().ofType(ArbitrationDecisionState.class);
    }

    @Test
    void anArbiterCycleWritesOneRowWhoseExecutedActionIsTheChosenActionOfThatCycle() {
        TestingHarness h = harness(a2());
        h.creature().emotions().regulate(Constants.HUNGER, 3.0);
        int cycles = 300;
        for (int i = 0; i < cycles; i++) {
            h.creature().setPosition(CREATURE_AT);
            seeApple(h);
            h.tick();
        }

        List<ChosenActionState> chosenRows = chosen(h);
        List<ArbitrationDecisionState> rows = arbitrationRows(h);
        assertEquals(chosenRows.size(), rows.size(), "one arbitration row per chosen-action row");
        for (int i = 0; i < rows.size(); i++) {
            assertEquals(i, rows.get(i).getSeq());
            assertEquals(i + 1L, rows.get(i).getCycle(), "decision cycles are contiguous from 1");
            assertEquals(chosenRows.get(i).getAction().name(), rows.get(i).getExecutedAction(),
                    "the executed action is the one the same cycle's chosen-action row records");
        }
    }

    // ---- 18. Issue #105: the join key of the episode return, and the executed action on both sides ----

    @Test
    void everyEpisodeStepHasExactlyOneArbitrationRowAtItsCycleWithTheSameExecutedAction() {
        TestingHarness h = harness(a2());
        h.creature().emotions().regulate(Constants.HUNGER, 3.0);
        trainApproachOnRedApple(h, Constants.HUNGER);
        trainApproachOnRedApple(h, Constants.PAIN);
        for (int i = 0; i < 5; i++) {
            seeApple(h);
            h.tick();
        }
        h.creature().emotions().regulate(Constants.PAIN, 6.0);
        for (int i = 0; i < 10; i++) {
            seeApple(h);
            h.tick();
        }

        List<EpisodeReturnState> steps = h.bdSink().ofType(EpisodeReturnState.class);
        assertFalse(steps.isEmpty(), "an episode closed in the harness");
        List<ArbitrationDecisionState> rows = arbitrationRows(h);
        for (EpisodeReturnState step : steps) {
            List<ArbitrationDecisionState> atCycle = rows.stream()
                    .filter(r -> r.getCreatureKey() == step.getCreatureKey() && r.getCycle() == step.getStepCycle())
                    .toList();
            assertEquals(1, atCycle.size(), "one arbitration row at cycle " + step.getStepCycle());
            assertEquals(step.getAction(), atCycle.get(0).getExecutedAction(),
                    "the step's action is the executed action of its decision");
        }
    }

    // ---- 19. Issue #105: flag off writes nothing ----------------------------------------------

    @Test
    void aFilterBasedRunWritesNoArbitrationRows() {
        TestingHarness h = harness(a0());
        h.creature().emotions().regulate(Constants.HUNGER, 3.0);
        seeApple(h);
        for (int i = 0; i < 20; i++) {
            h.tick();
        }
        assertTrue(arbitrationRows(h).isEmpty(), "no arbiter, no decision rows");
    }
}
