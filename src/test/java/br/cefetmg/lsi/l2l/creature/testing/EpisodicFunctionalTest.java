package br.cefetmg.lsi.l2l.creature.testing;

import br.cefetmg.lsi.l2l.cluster.settings.LearningSettings;
import br.cefetmg.lsi.l2l.common.Constants;
import br.cefetmg.lsi.l2l.common.Point;
import br.cefetmg.lsi.l2l.common.SequentialId;
import br.cefetmg.lsi.l2l.creature.arbiter.EngramKeyEncoder;
import br.cefetmg.lsi.l2l.creature.bd.ActionSelectionType;
import br.cefetmg.lsi.l2l.creature.bd.ArbitrationDecisionState;
import br.cefetmg.lsi.l2l.creature.bd.EpisodeReturnState;
import br.cefetmg.lsi.l2l.creature.bd.EpisodicEngramState;
import br.cefetmg.lsi.l2l.creature.conditioning.expectancy.ExpectancyMode;
import br.cefetmg.lsi.l2l.stimuli.LuminousStimulus;
import br.cefetmg.lsi.l2l.world.FruitType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A3 / A5 end to end through the in-process harness (issues #111-#116): a real creature whose closed episodes are
 * written as engrams into the episodic store and persisted, and whose arbiter consults the episodic controller.
 */
class EpisodicFunctionalTest {

    private static final Point CREATURE_AT = new Point(100, 100);
    private static final Point APPLE_AT = new Point(150, 100);
    private static final Point BOUNDS = new Point(1000, 1000);

    private static LearningSettings a3(boolean fixedOrder) {
        return new LearningSettings(false, false, List.of(ActionSelectionType.TARGET_DISTANCE),
                false, ExpectancyMode.DISCRETE, false, true, false, false, true, true, true,
                LearningSettings.ActionSelectorMode.ARBITER, 2.0, 0.5, true, LearningSettings.ActionTendencySet.CAMPOS,
                true, fixedOrder, 0.5);
    }

    private static TestingHarness harness(LearningSettings s) {
        return TestingHarness.builder().learningSettings(s).position(CREATURE_AT).worldBoundaries(BOUNDS).seed(117L).build();
    }

    private static void seeApple(TestingHarness h) {
        h.injectLuminous(new LuminousStimulus(new SequentialId(42L), new SequentialId(43L), FruitType.RED_APPLE, APPLE_AT));
    }

    /** Hunger opens an episode; pain past the critical threshold pre-empts it, which closes (writes) its steps. */
    private static void runAnEpisode(TestingHarness h, int cyclesBefore, int cyclesAfter) {
        h.creature().emotions().regulate(Constants.HUNGER, 3.0);
        for (int i = 0; i < cyclesBefore; i++) {
            h.creature().setPosition(CREATURE_AT);
            seeApple(h);
            h.tick();
        }
        h.creature().emotions().regulate(Constants.PAIN, 6.0);
        for (int i = 0; i < cyclesAfter; i++) {
            h.creature().setPosition(CREATURE_AT);
            seeApple(h);
            h.tick();
        }
    }

    @Test
    void closedEpisodesBecomeEngramsInTheStoreAndInTheTable() {
        TestingHarness h = harness(a3(false));
        runAnEpisode(h, 8, 10);

        List<EpisodeReturnState> steps = h.bdSink().ofType(EpisodeReturnState.class);
        List<EpisodicEngramState> rows = h.bdSink().ofType(EpisodicEngramState.class);
        assertFalse(steps.isEmpty(), "an episode closed");
        assertEquals(steps.size(), rows.size(), "one engram per closed step");
        for (int i = 0; i < rows.size(); i++) {
            assertEquals(i, rows.get(i).getSeq());
            assertEquals(steps.get(i).getStepCycle(), rows.get(i).getStepCycle());
            assertEquals(steps.get(i).getAction(), rows.get(i).getAction());
            assertEquals(steps.get(i).getEpisodeId(), rows.get(i).getEpisodeId());
            assertEquals(steps.get(i).getStepsToClose(), rows.get(i).getStepsToOutcome());
        }
        assertEquals(rows.size(), h.creature().episodicStore().size(), "the store holds what was persisted");
    }

    @Test
    void theArbiterConsultsTheEpisodicControllerOnceItHasEngrams() {
        TestingHarness h = harness(a3(false));
        runAnEpisode(h, 8, 10);
        // A second episode: the store is no longer empty when its decisions are made.
        for (int i = 0; i < 100; i++) {
            h.creature().setPosition(CREATURE_AT);
            seeApple(h);
            h.tick();
        }
        List<ArbitrationDecisionState> rows = h.bdSink().ofType(ArbitrationDecisionState.class);
        assertFalse(rows.isEmpty());
        // Telemetry carries the budget and the bonus for every decision.
        assertTrue(rows.stream().allMatch(r -> r.getAffordableController() != null && Double.isFinite(r.getEfficiency())));
        assertTrue(rows.stream().allMatch(r -> r.getNoveltyBonus() >= 0));
        assertTrue(rows.stream().anyMatch(r -> r.getNConsulted() >= 2),
                "with engrams in the store and efficiency above 0.3 the episodic controller is consulted");
        assertTrue(rows.stream().anyMatch(r -> "EPISODIC".equals(r.getDecidingController())),
                "and, where the operant table has no evidence, it decides");
        assertTrue(rows.stream().anyMatch(r -> !Double.isNaN(r.getControllerQ(
                br.cefetmg.lsi.l2l.creature.arbiter.Arbiter.ControllerType.EPISODIC))));
    }

    @Test
    void anOperantOnlyCreatureHasNoEpisodicStoreAndWritesNoEngrams() {
        LearningSettings a2 = new LearningSettings(false, false, List.of(ActionSelectionType.TARGET_DISTANCE),
                false, ExpectancyMode.DISCRETE, false, true, false, false, true, true, true,
                LearningSettings.ActionSelectorMode.ARBITER);
        TestingHarness h = harness(a2);
        runAnEpisode(h, 8, 10);
        assertNull(h.creature().episodicStore());
        assertTrue(h.bdSink().ofType(EpisodicEngramState.class).isEmpty());
        assertFalse(h.bdSink().ofType(EpisodeReturnState.class).isEmpty(), "episodes still close and persist");
    }

    @Test
    void theFixedOrderControlRunsTheSamePipeline() {
        TestingHarness h = harness(a3(true));
        runAnEpisode(h, 8, 10);
        assertFalse(h.bdSink().ofType(EpisodicEngramState.class).isEmpty());
        assertNotNull(h.creature().episodicStore());
        assertEquals(EngramKeyEncoder.KEY_DIM, 15);
    }

    @Test
    void episodicFlagsAreValidated() {
        List<ActionSelectionType> td = List.of(ActionSelectionType.TARGET_DISTANCE);
        // episodic without the arbiter
        assertThrows(IllegalArgumentException.class, () -> new LearningSettings(false, false, td, false,
                ExpectancyMode.DISCRETE, false, true, false, false, true, true, true,
                LearningSettings.ActionSelectorMode.FILTER_BASED, 2.0, 0.5, true,
                LearningSettings.ActionTendencySet.CAMPOS, true, false, 0.5));
        // fixed order without episodic
        assertThrows(IllegalArgumentException.class, () -> new LearningSettings(false, false, td, false,
                ExpectancyMode.DISCRETE, false, true, false, false, true, true, true,
                LearningSettings.ActionSelectorMode.ARBITER, 2.0, 0.5, true,
                LearningSettings.ActionTendencySet.CAMPOS, false, true, 0.5));
        // a bad lambda
        assertThrows(IllegalArgumentException.class, () -> new LearningSettings(false, false, td, false,
                ExpectancyMode.DISCRETE, false, true, false, false, true, true, true,
                LearningSettings.ActionSelectorMode.ARBITER, 2.0, 0.5, true,
                LearningSettings.ActionTendencySet.CAMPOS, true, false, -1));
        // the defaults of every older constructor
        LearningSettings plain = new LearningSettings();
        assertFalse(plain.isEpisodicEstimatorEnabled());
        assertFalse(plain.isArbiterFixedOrder());
        assertEquals(0.5, plain.getEpisodicKeyLambda(), 0.0);
    }
}
