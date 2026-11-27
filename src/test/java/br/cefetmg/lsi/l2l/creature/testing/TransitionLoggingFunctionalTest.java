package br.cefetmg.lsi.l2l.creature.testing;

import br.cefetmg.lsi.l2l.cluster.settings.LearningSettings;
import br.cefetmg.lsi.l2l.common.Constants;
import br.cefetmg.lsi.l2l.common.Point;
import br.cefetmg.lsi.l2l.common.SequentialId;
import br.cefetmg.lsi.l2l.creature.arbiter.EngramKeyEncoder;
import br.cefetmg.lsi.l2l.creature.arbiter.FocusTracker;
import br.cefetmg.lsi.l2l.creature.bd.ActionSelectionType;
import br.cefetmg.lsi.l2l.creature.bd.ArbitrationDecisionState;
import br.cefetmg.lsi.l2l.creature.bd.CycleOutcomeState;
import br.cefetmg.lsi.l2l.creature.bd.DecisionPerceptState;
import br.cefetmg.lsi.l2l.creature.bd.DecisionStepState;
import br.cefetmg.lsi.l2l.creature.bd.DriveOutcomeState;
import br.cefetmg.lsi.l2l.creature.components.FullAppraisal;
import br.cefetmg.lsi.l2l.creature.conditioning.expectancy.ExpectancyMode;
import br.cefetmg.lsi.l2l.stimuli.LuminousStimulus;
import br.cefetmg.lsi.l2l.world.FruitType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Plan A.3 (JEPA transition log) end to end through the in-process harness: a hungry creature with two apples in view,
 * one of them within contact, so EAT is in the candidate set and its drive outcome is attributed. Deterministic: fixed
 * seed, no wall-clock assertions.
 */
class TransitionLoggingFunctionalTest {

    private static final Point CREATURE_AT = new Point(100, 100);
    private static final Point BOUNDS = new Point(1000, 1000);
    private static final long SEED = 135L;
    private static final int CYCLES = 20;

    /** The A2 settings of {@link ArbitrationFunctionalTest}, with the transition log switched as asked. */
    private static LearningSettings settings(boolean transitionLogging) {
        return new LearningSettings(false, false, List.of(ActionSelectionType.TARGET_DISTANCE),
                false, ExpectancyMode.DISCRETE, false, true, false, false, true, true, true,
                LearningSettings.ActionSelectorMode.ARBITER,
                FocusTracker.Params.defaults().thetaOn(), FocusTracker.Params.defaults().thetaOff(),
                true, null, false, false, EngramKeyEncoder.DEFAULT_LAMBDA, true, null, null, true,
                LearningSettings.DEFAULT_ARBITER_THETA, transitionLogging);
    }

    private static TestingHarness harness(boolean transitionLogging) {
        return TestingHarness.builder().learningSettings(settings(transitionLogging)).position(CREATURE_AT)
                .worldBoundaries(BOUNDS).seed(SEED).build();
    }

    /** One apple within contact, one 50 units east: two perceptions in every cycle. */
    private static void seeTwoApples(TestingHarness h) {
        double near = FullAppraisal.contactDistance(FruitType.RED_APPLE) * 0.5;
        h.injectLuminous(
                new LuminousStimulus(new SequentialId(42L), new SequentialId(43L), FruitType.RED_APPLE,
                        new Point(CREATURE_AT.x + near, CREATURE_AT.y)),
                new LuminousStimulus(new SequentialId(44L), new SequentialId(45L), FruitType.RED_APPLE,
                        new Point(CREATURE_AT.x + 50, CREATURE_AT.y)));
    }

    private static void run(TestingHarness h) {
        h.creature().emotions().regulate(Constants.HUNGER, 3.0);
        for (int i = 0; i < CYCLES; i++) {
            h.creature().setPosition(CREATURE_AT);
            seeTwoApples(h);
            h.tick();
        }
    }

    private static double sum(List<Double> values) {
        return values.stream().mapToDouble(Double::doubleValue).sum();
    }

    @Test
    void everyDecisionWritesOneStepRowAndItsPerceptsAtTheSameCycle() {
        TestingHarness h = harness(true);
        run(h);

        List<ArbitrationDecisionState> arbitration = h.bdSink().ofType(ArbitrationDecisionState.class);
        List<DecisionStepState> steps = h.bdSink().ofType(DecisionStepState.class);
        List<DecisionPerceptState> percepts = h.bdSink().ofType(DecisionPerceptState.class);
        assertFalse(arbitration.isEmpty(), "the arbiter decided");

        assertEquals(arbitration.size(), steps.size(), "decision_steps count equals arbitration_decisions count");
        Map<Long, DecisionStepState> stepByCycle = steps.stream()
                .collect(Collectors.toMap(DecisionStepState::getCycle, s -> s));
        assertEquals(steps.size(), stepByCycle.size(), "one step row per cycle");
        for (ArbitrationDecisionState a : arbitration) {
            DecisionStepState s = stepByCycle.get(a.getCycle());
            assertNotNull(s, "a step row at cycle " + a.getCycle());
            assertEquals(a.getH(0), s.getH(0), 0.0, "the drive levels agree with the arbitration row");
            assertEquals(a.getExecutedTargetKey(), s.getTargetKey(), "the executed target agrees");
        }

        Set<Long> decisionCycles = arbitration.stream().map(ArbitrationDecisionState::getCycle).collect(Collectors.toSet());
        Map<Long, List<DecisionPerceptState>> byCycle = percepts.stream()
                .collect(Collectors.groupingBy(DecisionPerceptState::getCycle));
        assertEquals(decisionCycles, byCycle.keySet(), "percept rows carry exactly the decisions' cycles");
        for (long cycle : decisionCycles) {
            List<DecisionPerceptState> rows = byCycle.get(cycle);
            assertEquals(2, rows.size(), "one percept row per perception, two apples in view, at cycle " + cycle);
            assertEquals(List.of(0, 1), rows.stream().map(DecisionPerceptState::getRank).sorted().toList());
            assertTrue(rows.stream().anyMatch(DecisionPerceptState::isContact), "the near apple is in contact");
        }
    }

    @Test
    void cycleOutcomesEqualTheNonBaselineDriveOutcomesOverTheRun() {
        TestingHarness h = harness(true);
        run(h);

        List<DriveOutcomeState> attributed = h.bdSink().ofType(DriveOutcomeState.class).stream()
                .filter(o -> !DriveOutcomeState.BASELINE.equals(o.getAction()))
                .toList();
        List<CycleOutcomeState> cycles = h.bdSink().ofType(CycleOutcomeState.class);
        assertFalse(attributed.isEmpty(), "the run includes an attributed outcome (the EAT)");
        assertEquals(attributed.size(), cycles.size(), "one cycle_outcomes row per attributed outcome");

        assertEquals(sum(attributed.stream().map(DriveOutcomeState::getDHunger).toList()),
                sum(cycles.stream().map(c -> c.getD(0)).toList()), 1e-9, "sum of d_hunger");
        assertEquals(sum(attributed.stream().map(DriveOutcomeState::getDSleep).toList()),
                sum(cycles.stream().map(c -> c.getD(1)).toList()), 1e-9, "sum of d_sleep");
        assertEquals(sum(attributed.stream().map(DriveOutcomeState::getDPain).toList()),
                sum(cycles.stream().map(c -> c.getD(2)).toList()), 1e-9, "sum of d_pain");
        assertEquals(sum(attributed.stream().map(DriveOutcomeState::getDTedium).toList()),
                sum(cycles.stream().map(c -> c.getD(3)).toList()), 1e-9, "sum of d_tedium");
    }

    @Test
    void withTheFlagOffNoTransitionRowIsWrittenAndTheArbiterStillRuns() {
        TestingHarness h = harness(false);
        run(h);

        assertFalse(h.bdSink().ofType(ArbitrationDecisionState.class).isEmpty(), "the arbiter still decides");
        assertTrue(h.bdSink().ofType(DecisionStepState.class).isEmpty(), "no decision_steps rows");
        assertTrue(h.bdSink().ofType(DecisionPerceptState.class).isEmpty(), "no decision_percepts rows");
        assertTrue(h.bdSink().ofType(CycleOutcomeState.class).isEmpty(), "no cycle_outcomes rows");
    }
}
