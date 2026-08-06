package br.cefetmg.lsi.l2l.creature.components;

import br.cefetmg.lsi.l2l.cluster.settings.LearningSettings;
import br.cefetmg.lsi.l2l.common.Point;
import br.cefetmg.lsi.l2l.common.SequentialId;
import br.cefetmg.lsi.l2l.creature.bd.ActionSelectionType;
import br.cefetmg.lsi.l2l.creature.bd.ChosenActionState;
import br.cefetmg.lsi.l2l.creature.bd.MemoryDecisionState;
import br.cefetmg.lsi.l2l.creature.testing.TestingHarness;
import br.cefetmg.lsi.l2l.stimuli.LuminousStimulus;
import br.cefetmg.lsi.l2l.world.FruitType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that FullAppraisal logs what the episodic-memory filter had to go on (issue #84).
 *
 * <p>{@code chosen_action_state.actionselectiontype = MEMORY} already says memory won a decision;
 * these rows say how much evidence it had, which is what distinguishes a working mechanism from a
 * merely wired-up one. The two behaviours worth pinning down are that a row appears only when the
 * filter is actually in the chain, and that a cycle which never reaches the filter logs nothing
 * rather than re-logging the previous cycle's decision.
 */
public class MemoryDecisionPersistenceTest {

    private static LearningSettings withMemory() {
        return new LearningSettings(true, false,
                List.of(ActionSelectionType.TARGET_DISTANCE,
                        ActionSelectionType.AFFORDANCE,
                        ActionSelectionType.MEMORY,
                        ActionSelectionType.RANDOM));
    }

    private static LearningSettings withoutMemory() {
        return new LearningSettings(true, false,
                List.of(ActionSelectionType.TARGET_DISTANCE,
                        ActionSelectionType.AFFORDANCE,
                        ActionSelectionType.RANDOM));
    }

    /** Puts two visible apples in front of the creature so a decision has something to choose between. */
    private static void showTwoApples(TestingHarness h) {
        Point p = h.creature().getPosition();
        SequentialId red = new SequentialId(84_001L);
        SequentialId green = new SequentialId(84_002L);
        h.injectLuminous(
                new LuminousStimulus(red, red.next(), FruitType.RED_APPLE,
                        new Point(p.x + 40, p.y)),
                new LuminousStimulus(green, green.next(), FruitType.GREEN_APPLE,
                        new Point(p.x + 60, p.y)));
    }

    @Test
    void a_consultation_is_logged_when_the_memory_filter_is_in_the_chain() {
        TestingHarness h = TestingHarness.builder().learningSettings(withMemory()).build();

        showTwoApples(h);
        h.tick();

        List<ChosenActionState> chosen = h.bdSink().ofType(ChosenActionState.class);
        assertFalse(chosen.isEmpty(), "the cycle must have reached a decision for this test to mean anything");

        List<MemoryDecisionState> decisions = h.bdSink().ofType(MemoryDecisionState.class);
        assertFalse(decisions.isEmpty(),
                "MemoryFilter was in the chain, so at least one consultation should have been logged");
        assertTrue(decisions.stream().allMatch(d -> d.getCandidates() >= 0));
    }

    @Test
    void nothing_is_logged_when_the_memory_filter_is_disabled() {
        TestingHarness h = TestingHarness.builder().learningSettings(withoutMemory()).build();

        showTwoApples(h);
        h.tick();

        assertFalse(h.bdSink().ofType(ChosenActionState.class).isEmpty(),
                "the cycle must have reached a decision for this test to mean anything");
        assertTrue(h.bdSink().ofType(MemoryDecisionState.class).isEmpty(),
                "no MEMORY filter in the chain means no consultations to log");
    }

    @Test
    void consultations_are_never_logged_more_often_than_decisions() {
        TestingHarness h = TestingHarness.builder().learningSettings(withMemory()).build();

        showTwoApples(h);
        for (int i = 0; i < 5; i++) {
            h.tick();
        }

        int decisions = h.bdSink().ofType(ChosenActionState.class).size();
        int consultations = h.bdSink().ofType(MemoryDecisionState.class).size();

        assertTrue(consultations <= decisions,
                "takeLastDecision() clears the record, so a cycle that never reached the filter "
                        + "must not re-log a stale one (got " + consultations + " consultations "
                        + "for " + decisions + " decisions)");
    }

    @Test
    void logged_consultations_carry_distinct_sequence_numbers() {
        TestingHarness h = TestingHarness.builder().learningSettings(withMemory()).build();

        showTwoApples(h);
        for (int i = 0; i < 5; i++) {
            h.tick();
        }

        List<Long> seqs = h.bdSink().ofType(MemoryDecisionState.class).stream()
                .map(MemoryDecisionState::getSeq).toList();
        assertEquals(seqs.size(), seqs.stream().distinct().count(),
                "each logged consultation needs its own seq to be orderable within a cycle");
    }
}
