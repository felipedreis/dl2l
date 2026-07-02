package br.cefetmg.lsi.l2l.creature.ml;

import br.cefetmg.lsi.l2l.common.Constants;
import br.cefetmg.lsi.l2l.common.SequentialId;
import br.cefetmg.lsi.l2l.creature.common.ActionType;
import br.cefetmg.lsi.l2l.creature.common.Perception;
import br.cefetmg.lsi.l2l.creature.components.Emotion;
import br.cefetmg.lsi.l2l.creature.memory.Engram;
import br.cefetmg.lsi.l2l.world.FruitType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for MemoryTraceConsolidator.consolidate() — the core consolidation logic.
 * No Akka cluster or database required.
 */
public class MemoryTraceConsolidatorTest {

    private static final long CYCLE = 100L;

    // -----------------------------------------------------------------------
    // Empty / no-op cases
    // -----------------------------------------------------------------------

    @Test
    void empty_engram_list_produces_no_consolidated_engrams() {
        List<Engram> result = MemoryTraceConsolidator.consolidate(List.of(), CYCLE);
        assertTrue(result.isEmpty());
    }

    @Test
    void harmful_group_below_threshold_is_not_consolidated() {
        // emotionDelta = +1.0 (emotion increased) → mean(-emotionDelta) = -1.0 < threshold
        List<Engram> engrams = List.of(
                engram(ActionType.APPROACH, FruitType.RED_APPLE, +1.0, 0.9)
        );
        List<Engram> result = MemoryTraceConsolidator.consolidate(engrams, CYCLE);
        assertTrue(result.isEmpty());
    }

    @Test
    void neutral_group_exactly_at_threshold_is_not_consolidated() {
        // mean(-emotionDelta) == MEMORY_CONSOLIDATION_THRESHOLD → must be strictly greater
        double delta = -Constants.MEMORY_CONSOLIDATION_THRESHOLD;
        List<Engram> engrams = List.of(engram(ActionType.APPROACH, FruitType.RED_APPLE, delta, 1.0));
        List<Engram> result = MemoryTraceConsolidator.consolidate(engrams, CYCLE);
        assertTrue(result.isEmpty());
    }

    // -----------------------------------------------------------------------
    // Consolidation scenarios
    // -----------------------------------------------------------------------

    @Test
    void beneficial_group_above_threshold_produces_consolidated_engram() {
        // emotionDelta = -0.5 → mean(-emotionDelta) = 0.5 > 0.1 (threshold)
        List<Engram> engrams = List.of(
                engram(ActionType.APPROACH, FruitType.RED_APPLE, -0.5, 0.8)
        );
        List<Engram> result = MemoryTraceConsolidator.consolidate(engrams, CYCLE);

        assertEquals(1, result.size());
        Engram c = result.get(0);
        assertEquals(ActionType.APPROACH, c.actionType());
        assertEquals(1.0, c.eligibility(), 1e-9);
        assertEquals(-0.5, c.emotionDelta(), 1e-9);
        assertEquals(CYCLE, c.layCycle());
        assertEquals(CYCLE, c.reinforcedCycle());
    }

    @Test
    void consolidated_engram_eligibility_is_always_1() {
        List<Engram> engrams = List.of(
                engram(ActionType.EAT, FruitType.GREEN_APPLE, -2.0, 0.3),
                engram(ActionType.EAT, FruitType.GREEN_APPLE, -1.0, 0.1)
        );
        List<Engram> result = MemoryTraceConsolidator.consolidate(engrams, CYCLE);
        assertEquals(1, result.size());
        assertEquals(1.0, result.get(0).eligibility(), 1e-9);
    }

    @Test
    void emotion_delta_of_consolidated_engram_is_mean_of_group() {
        List<Engram> engrams = List.of(
                engram(ActionType.EAT, FruitType.RED_APPLE, -1.0, 1.0),
                engram(ActionType.EAT, FruitType.RED_APPLE, -3.0, 1.0)
        );
        List<Engram> result = MemoryTraceConsolidator.consolidate(engrams, CYCLE);

        assertEquals(1, result.size());
        assertEquals(-2.0, result.get(0).emotionDelta(), 1e-9);
    }

    @Test
    void groups_are_separated_by_action_and_object_type() {
        // Two different (action, objectType) pairs, both beneficial.
        List<Engram> engrams = List.of(
                engram(ActionType.APPROACH, FruitType.RED_APPLE,   -1.0, 1.0),
                engram(ActionType.EAT,      FruitType.GREEN_APPLE, -0.8, 1.0)
        );
        List<Engram> result = MemoryTraceConsolidator.consolidate(engrams, CYCLE);
        assertEquals(2, result.size());
    }

    @Test
    void only_beneficial_groups_are_consolidated_harmful_ones_are_skipped() {
        List<Engram> engrams = List.of(
                engram(ActionType.APPROACH, FruitType.RED_APPLE,   -1.0, 1.0),  // good  → consolidated
                engram(ActionType.APPROACH, FruitType.GREEN_APPLE, +2.0, 1.0)   // harmful → skipped
        );
        List<Engram> result = MemoryTraceConsolidator.consolidate(engrams, CYCLE);

        assertEquals(1, result.size());
        assertEquals(FruitType.RED_APPLE,
                result.get(0).perception().objectType.getOrElse(null));
    }

    @Test
    void null_object_type_is_handled_as_a_separate_group() {
        // WANDER with null objectType should be treated as its own group.
        List<Engram> engrams = List.of(
                engram(ActionType.WANDER, null, -0.5, 1.0),
                engram(ActionType.SLEEP,  null, -0.5, 1.0)
        );
        List<Engram> result = MemoryTraceConsolidator.consolidate(engrams, CYCLE);
        assertEquals(2, result.size());
    }

    @Test
    void mixed_engrams_average_correctly_and_may_cross_threshold() {
        // Three engrams for same pair: deltas -0.8, +0.4, -0.4  → mean = -0.267
        // mean(-emotionDelta) = +0.267 > 0.1 → should consolidate
        List<Engram> engrams = List.of(
                engram(ActionType.EAT, FruitType.RED_APPLE, -0.8, 1.0),
                engram(ActionType.EAT, FruitType.RED_APPLE, +0.4, 1.0),
                engram(ActionType.EAT, FruitType.RED_APPLE, -0.4, 1.0)
        );
        List<Engram> result = MemoryTraceConsolidator.consolidate(engrams, CYCLE);

        assertEquals(1, result.size());
        assertEquals(-0.8 / 3.0 + 0.4 / 3.0 + -0.4 / 3.0, result.get(0).emotionDelta(), 1e-9);
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static Engram engram(ActionType action, FruitType objectType,
                                 double emotionDelta, double eligibility) {
        Perception p = new Perception(objectType, new SequentialId(1), 10.0, 0.0);
        Emotion e = new Emotion("hunger");
        e.setLevel(3.0);
        return new Engram(action, new SequentialId(1), e, p, CYCLE, emotionDelta, eligibility, CYCLE);
    }
}
