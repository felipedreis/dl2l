package br.cefetmg.lsi.l2l.creature.components;

import br.cefetmg.lsi.l2l.cluster.settings.LearningSettings;
import br.cefetmg.lsi.l2l.common.Constants;
import br.cefetmg.lsi.l2l.common.SequentialId;
import br.cefetmg.lsi.l2l.creature.bd.ActionProbabilityState;
import br.cefetmg.lsi.l2l.creature.bd.ActionSelectionType;
import br.cefetmg.lsi.l2l.creature.common.ActionType;
import br.cefetmg.lsi.l2l.creature.conditioning.expectancy.ExpectancyContext;
import br.cefetmg.lsi.l2l.creature.conditioning.expectancy.ExpectancyMode;
import br.cefetmg.lsi.l2l.creature.testing.TestingHarness;
import br.cefetmg.lsi.l2l.stimuli.EvaluationStimulus;
import br.cefetmg.lsi.l2l.world.FruitType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that Valuation snapshots the operant conditioning table on every reinforcement
 * (issue #84). The table used to be mutated purely in memory and never written anywhere, so the
 * learned conditioning trajectory was unrecoverable after a run.
 *
 * <p>The legacy-path coverage is the point of this class: ExpectancyState is written only on the
 * expectancy path, so a conditioning snapshot hooked only there would leave the legacy-minimal
 * parity arms — which run with expectancy disabled — with no conditioning data at all.
 */
public class ValuationConditioningPersistenceTest {

    private static final SequentialId SID = new SequentialId(777L);

    private static LearningSettings expectancyOn() {
        return new LearningSettings(true, false,
                List.of(ActionSelectionType.TARGET_DISTANCE, ActionSelectionType.AFFORDANCE, ActionSelectionType.RANDOM),
                true, ExpectancyMode.DISCRETE, false);
    }

    private static LearningSettings expectancyOff() {
        return new LearningSettings(true, false,
                List.of(ActionSelectionType.TARGET_DISTANCE, ActionSelectionType.AFFORDANCE, ActionSelectionType.RANDOM));
    }

    /** arousalVariation < 0 means the drive fell, i.e. a pleasant outcome. */
    private static EvaluationStimulus eatApple(double arousalVariation) {
        Emotion hunger = new Emotion(Constants.HUNGER);
        hunger.setLevel(6.0);
        return new EvaluationStimulus(SID, SID.next(), SID, FruitType.RED_APPLE, ActionType.EAT,
                hunger, arousalVariation, new ExpectancyContext(Constants.HUNGER, 6.0));
    }

    private static List<ActionProbabilityState> rows(TestingHarness h) {
        return h.bdSink().ofType(ActionProbabilityState.class);
    }

    @Test
    void legacy_path_snapshots_the_whole_table_for_the_evaluated_target() {
        TestingHarness h = TestingHarness.builder().learningSettings(expectancyOff()).build();

        h.inject(Valuation.class, eatApple(-2.0));

        List<ActionProbabilityState> states = rows(h);
        assertFalse(states.isEmpty(),
                "the legacy path must persist conditioning too — it writes no ExpectancyState");

        Set<ActionType> actions = states.stream()
                .map(ActionProbabilityState::getAction).collect(Collectors.toSet());
        assertEquals(6, actions.size(), "one row per action in the target's table");

        assertTrue(states.stream().allMatch(s -> FruitType.RED_APPLE.name().equals(s.getTarget())),
                "only the evaluated target is snapshotted");
        assertTrue(states.stream().allMatch(s -> s.getReinforcedAction() == ActionType.EAT));
    }

    @Test
    void a_pleasant_outcome_raises_the_reinforced_action_and_lowers_the_rest() {
        TestingHarness h = TestingHarness.builder().learningSettings(expectancyOff()).build();

        h.inject(Valuation.class, eatApple(-2.0));

        List<ActionProbabilityState> states = rows(h);
        double eat = states.stream().filter(s -> s.getAction() == ActionType.EAT)
                .findFirst().orElseThrow().getProbability();
        double approach = states.stream().filter(s -> s.getAction() == ActionType.APPROACH)
                .findFirst().orElseThrow().getProbability();

        // ProbabilityBasedExperience starts EAT and APPROACH at 25; a +1 delta on EAT is
        // compensated by -1/5 = -0.2 spread across the other five actions.
        assertEquals(26.0, eat, 1e-9);
        assertEquals(24.8, approach, 1e-9);
        assertEquals(1.0, states.get(0).getDelta(), 1e-9, "positive valence is a positive delta");
    }

    @Test
    void an_unpleasant_outcome_records_a_negative_delta() {
        TestingHarness h = TestingHarness.builder().learningSettings(expectancyOff()).build();

        h.inject(Valuation.class, eatApple(+2.0));

        List<ActionProbabilityState> states = rows(h);
        assertFalse(states.isEmpty());
        assertEquals(-1.0, states.get(0).getDelta(), 1e-9);
        double eat = states.stream().filter(s -> s.getAction() == ActionType.EAT)
                .findFirst().orElseThrow().getProbability();
        assertEquals(24.0, eat, 1e-9);
    }

    @Test
    void expectancy_path_snapshots_the_table_as_well() {
        TestingHarness h = TestingHarness.builder().learningSettings(expectancyOn()).build();

        h.inject(Valuation.class, eatApple(-2.0));

        List<ActionProbabilityState> states = rows(h);
        assertFalse(states.isEmpty(), "the expectancy path must persist conditioning too");
        assertTrue(states.stream().allMatch(s -> s.getReinforcedAction() == ActionType.EAT));
        // First reward: expected starts at the neutral prior 0, so rpe = reward = +2.0.
        assertEquals(2.0, states.get(0).getDelta(), 1e-9);
    }

    @Test
    void each_reinforcement_gets_its_own_seq() {
        TestingHarness h = TestingHarness.builder().learningSettings(expectancyOff()).build();

        h.inject(Valuation.class, eatApple(-2.0));
        h.inject(Valuation.class, eatApple(-2.0));

        List<Long> seqs = rows(h).stream()
                .map(ActionProbabilityState::getSeq).distinct().sorted().toList();
        assertEquals(List.of(0L, 1L), seqs, "two reinforcements, two snapshots");
    }
}
