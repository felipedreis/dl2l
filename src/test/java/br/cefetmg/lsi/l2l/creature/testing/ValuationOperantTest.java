package br.cefetmg.lsi.l2l.creature.testing;

import br.cefetmg.lsi.l2l.cluster.settings.LearningSettings;
import br.cefetmg.lsi.l2l.common.Constants;
import br.cefetmg.lsi.l2l.common.SequentialId;
import br.cefetmg.lsi.l2l.creature.arbiter.Estimate;
import br.cefetmg.lsi.l2l.creature.bd.OperantUpdateState;
import br.cefetmg.lsi.l2l.creature.common.Action;
import br.cefetmg.lsi.l2l.creature.common.ActionType;
import br.cefetmg.lsi.l2l.creature.common.Perception;
import br.cefetmg.lsi.l2l.creature.components.Emotion;
import br.cefetmg.lsi.l2l.creature.conditioning.expectancy.ExpectancyContext;
import br.cefetmg.lsi.l2l.creature.conditioning.expectancy.ExpectancyMode;
import br.cefetmg.lsi.l2l.stimuli.EvaluationStimulus;
import br.cefetmg.lsi.l2l.world.FruitType;
import br.cefetmg.lsi.l2l.world.PlantType;
import br.cefetmg.lsi.l2l.world.Self;
import br.cefetmg.lsi.l2l.world.WorldObjectType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The operant table learns per outcome: {@code Valuation} records {@code -arousalVariation} into the
 * {@code (target, executedAction)} cell for every {@code EvaluationStimulus} on a fruit or plant, on both the
 * legacy and the expectancy path, and ignores outcomes on the creature itself (like the legacy table).
 */
class ValuationOperantTest {

    private static final double[] H = new double[4];
    private static final double TOL = 1e-9;
    private static final Estimate PRIOR = new Estimate(0, 1, 1);

    /** Operant estimator on, focal drive off: the table does not depend on focus episodes. */
    private static LearningSettings settings(boolean expectancy) {
        return new LearningSettings(false, false, List.of(), expectancy, ExpectancyMode.DISCRETE,
                false, false, false, false, true, false, true);
    }

    private static TestingHarness harness(boolean expectancy) {
        return TestingHarness.builder().learningSettings(settings(expectancy)).build();
    }

    private static EvaluationStimulus outcome(WorldObjectType type, ActionType action, double arousalVariation) {
        return new EvaluationStimulus(new SequentialId(1), new SequentialId(2), new SequentialId(3), type, action,
                new Emotion(Constants.HUNGER), arousalVariation, new ExpectancyContext(Constants.HUNGER, 1.0));
    }

    private static Estimate cell(TestingHarness h, WorldObjectType type, ActionType action) {
        Action a = new Action(action, new Perception(type, new SequentialId(9), 33, 0));
        return h.creature().operantStore().estimate(List.of(a), H, null).get(0);
    }

    private static void deliver(TestingHarness h, EvaluationStimulus e) {
        h.creature().valuation().tell(e);
        h.processUntilQuiescent();
    }

    @Test
    void aNutritiveEatOnAFruitRecordsTheDriveReductionOnTheLegacyPath() {
        TestingHarness h = harness(false);
        deliver(h, outcome(FruitType.RED_APPLE, ActionType.EAT, -1.5));

        // sample = -(-1.5) = 1.5; Q = (1 * 1.5 + 0) / (1 + 1)
        assertEquals(0.75, cell(h, FruitType.RED_APPLE, ActionType.EAT).q(), TOL);
        assertEquals(PRIOR, cell(h, FruitType.GREEN_APPLE, ActionType.EAT), "another object type");
        assertEquals(PRIOR, cell(h, FruitType.RED_APPLE, ActionType.APPROACH), "another action");

        List<OperantUpdateState> rows = h.bdSink().ofType(OperantUpdateState.class);
        assertEquals(1, rows.size());
        OperantUpdateState r = rows.get(0);
        assertEquals("RED_APPLE", r.getObjectType());
        assertEquals("EAT", r.getAction());
        assertEquals(1.5, r.getSample(), TOL);
        assertEquals(1.0, r.getN(), TOL);
        assertEquals(0.75, r.getQ(), TOL);
        assertEquals(1 / Math.sqrt(2), r.getSe(), TOL);
        assertEquals(1.0, r.getSigma(), TOL);
    }

    @Test
    void withLearningSwitchedOffTheTableStaysAtThePrior() {
        LearningSettings frozen = new LearningSettings(false, false, List.of(), false, ExpectancyMode.DISCRETE,
                false, false, false, false, true, false, true, LearningSettings.ActionSelectorMode.FILTER_BASED,
                2.0, 0.5, false);
        TestingHarness h = TestingHarness.builder().learningSettings(frozen).build();
        deliver(h, outcome(FruitType.RED_APPLE, ActionType.EAT, -1.5));

        assertEquals(PRIOR, cell(h, FruitType.RED_APPLE, ActionType.EAT));
        assertTrue(h.bdSink().ofType(OperantUpdateState.class).isEmpty());
    }

    @Test
    void aHarmfulOutcomeRecordsANegativeSample() {
        TestingHarness h = harness(false);
        deliver(h, outcome(FruitType.RED_APPLE, ActionType.EAT, 2.0));

        assertEquals(-1.0, cell(h, FruitType.RED_APPLE, ActionType.EAT).q(), TOL);
    }

    @Test
    void theExpectancyPathRecordsTheSameMagnitudeNotTheRpe() {
        TestingHarness h = harness(true);
        deliver(h, outcome(FruitType.RED_APPLE, ActionType.EAT, -1.5));

        assertEquals(0.75, cell(h, FruitType.RED_APPLE, ActionType.EAT).q(), TOL);
        assertEquals(1, h.bdSink().ofType(OperantUpdateState.class).size());
    }

    @Test
    void aPlantTargetIsKept() {
        TestingHarness h = harness(false);
        deliver(h, outcome(PlantType.values()[0], ActionType.EAT, -1.0));

        assertEquals(0.5, cell(h, PlantType.values()[0], ActionType.EAT).q(), TOL);
    }

    @Test
    void anOutcomeOnTheCreatureItselfIsIgnoredOnBothPaths() {
        for (boolean expectancy : new boolean[] {false, true}) {
            TestingHarness h = harness(expectancy);
            deliver(h, outcome(Self.get(), ActionType.SLEEP, -1.0));
            deliver(h, outcome(Self.get(), ActionType.WANDER, -1.0));

            Action sleep = new Action(ActionType.SLEEP, new Perception(Self.get(), new SequentialId(7), 0, 0));
            assertEquals(PRIOR, h.creature().operantStore().estimate(List.of(sleep), H, null).get(0));
            assertEquals(PRIOR, cell(h, null, ActionType.WANDER));
            assertTrue(h.bdSink().ofType(OperantUpdateState.class).isEmpty(), "expectancy=" + expectancy);
        }
    }

    @Test
    void successiveOutcomesAccumulateInOneCellWhateverTheDistance() {
        TestingHarness h = harness(false);
        deliver(h, outcome(FruitType.RED_APPLE, ActionType.EAT, -1.0));
        deliver(h, outcome(FruitType.RED_APPLE, ActionType.EAT, -1.0));

        // two samples of 1.0 (decay 0.995 on the count): n = 1.995, Q = n / (n + 1)
        double n = 0.995 + 1;
        assertEquals(n / (n + 1), cell(h, FruitType.RED_APPLE, ActionType.EAT).q(), TOL);
        List<OperantUpdateState> rows = h.bdSink().ofType(OperantUpdateState.class);
        assertEquals(List.of(0L, 1L), rows.stream().map(OperantUpdateState::getSeq).toList());
        assertEquals(n, rows.get(1).getN(), TOL);
    }
}
