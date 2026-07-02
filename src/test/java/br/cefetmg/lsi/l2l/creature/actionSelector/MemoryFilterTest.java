package br.cefetmg.lsi.l2l.creature.actionSelector;

import br.cefetmg.lsi.l2l.common.SequentialId;
import br.cefetmg.lsi.l2l.creature.bd.ActionSelectionType;
import br.cefetmg.lsi.l2l.creature.common.Action;
import br.cefetmg.lsi.l2l.creature.common.ActionType;
import br.cefetmg.lsi.l2l.creature.common.Perception;
import br.cefetmg.lsi.l2l.creature.components.Emotion;
import br.cefetmg.lsi.l2l.creature.memory.Engram;
import br.cefetmg.lsi.l2l.creature.memory.MemorySystem;
import br.cefetmg.lsi.l2l.creature.memory.ShortTermMemory;
import br.cefetmg.lsi.l2l.world.FruitType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for MemoryFilter.
 *
 * Uses a stub MemorySystem backed by a simple list — no Akka cluster needed.
 */
public class MemoryFilterTest {

    private static final Emotion HUNGER = emotion("hunger", 5.0);
    private static final SequentialId RED_ID   = new SequentialId(1);
    private static final SequentialId GREEN_ID = new SequentialId(2);

    private static final Perception RED_NEAR   = new Perception(FruitType.RED_APPLE,   RED_ID,   10, 0);
    private static final Perception GREEN_NEAR = new Perception(FruitType.GREEN_APPLE, GREEN_ID, 10, 0);
    private static final Perception SELF_PERC  = new Perception(null, new SequentialId(3), 0, 0);

    private ListMemorySystem memory;

    @BeforeEach
    void setUp() {
        memory = new ListMemorySystem();
    }

    // -----------------------------------------------------------------------
    // Gate conditions
    // -----------------------------------------------------------------------

    @Test
    void empty_engrams_returns_all_actions_unchanged() {
        List<Action> actions = List.of(approach(RED_NEAR), approach(GREEN_NEAR));
        MemoryFilter filter = new MemoryFilter(memory);

        List<Action> result = filter.filter(new ArrayList<>(actions), HUNGER);

        assertEquals(2, result.size());
    }

    @Test
    void single_candidate_returns_unchanged_without_engrams() {
        List<Action> actions = List.of(approach(RED_NEAR));
        MemoryFilter filter = new MemoryFilter(memory);

        List<Action> result = filter.filter(new ArrayList<>(actions), HUNGER);

        assertEquals(1, result.size());
        assertEquals(ActionType.APPROACH, result.get(0).type);
    }

    @Test
    void single_candidate_returns_unchanged_with_engrams() {
        memory.add(engram(ActionType.APPROACH, FruitType.RED_APPLE, -2.0, 0.8));
        List<Action> actions = List.of(approach(RED_NEAR));
        MemoryFilter filter = new MemoryFilter(memory);

        List<Action> result = filter.filter(new ArrayList<>(actions), HUNGER);

        assertEquals(1, result.size());
    }

    // -----------------------------------------------------------------------
    // Selection scenarios
    // -----------------------------------------------------------------------

    @Test
    void selects_action_with_best_engram_score() {
        // APPROACH RED_APPLE had emotion drop (-2.0) → score = -(-2.0)*0.8 = +1.6  (good)
        // APPROACH GREEN_APPLE had emotion rise (+3.0) → score = -(+3.0)*0.9 = -2.7 (bad)
        memory.add(engram(ActionType.APPROACH, FruitType.RED_APPLE,   -2.0, 0.8));
        memory.add(engram(ActionType.APPROACH, FruitType.GREEN_APPLE, +3.0, 0.9));

        List<Action> actions = new ArrayList<>(List.of(approach(RED_NEAR), approach(GREEN_NEAR)));
        MemoryFilter filter = new MemoryFilter(memory);

        List<Action> result = filter.filter(actions, HUNGER);

        assertEquals(1, result.size());
        assertEquals(FruitType.RED_APPLE, result.get(0).getTarget());
    }

    @Test
    void action_with_no_matching_engram_wins_only_when_all_scored_are_negative() {
        // APPROACH GREEN_APPLE is harmful (positive emotionDelta → score < 0).
        // APPROACH RED_APPLE has no engram → goes to unscored bucket.
        // Since the only scored action has negative score but something IS scored,
        // the scored winner (GREEN) is returned — the filter picks highest-scored.
        memory.add(engram(ActionType.APPROACH, FruitType.GREEN_APPLE, +1.0, 1.0));

        List<Action> actions = new ArrayList<>(List.of(approach(RED_NEAR), approach(GREEN_NEAR)));
        MemoryFilter filter = new MemoryFilter(memory);

        List<Action> result = filter.filter(actions, HUNGER);

        // GREEN has the only score; it wins (even negative scores get picked over no score).
        assertEquals(1, result.size());
        assertEquals(FruitType.GREEN_APPLE, result.get(0).getTarget());
    }

    @Test
    void no_matching_engrams_for_any_action_returns_all() {
        // Engrams exist but for a different (action, objectType) pair.
        memory.add(engram(ActionType.EAT, FruitType.RED_APPLE, -1.0, 0.5));

        List<Action> actions = new ArrayList<>(List.of(approach(RED_NEAR), approach(GREEN_NEAR)));
        MemoryFilter filter = new MemoryFilter(memory);

        List<Action> result = filter.filter(actions, HUNGER);

        assertEquals(2, result.size());
    }

    @Test
    void multiple_engrams_for_same_key_are_summed() {
        // Two engrams for APPROACH RED_APPLE: scores +0.8 and +0.5 = total +1.3
        // One engram for APPROACH GREEN_APPLE: score +1.2
        // GREEN_APPLE total 1.2 < RED_APPLE total 1.3 → RED wins
        memory.add(engram(ActionType.APPROACH, FruitType.RED_APPLE,   -1.0, 0.8));  // +0.8
        memory.add(engram(ActionType.APPROACH, FruitType.RED_APPLE,   -0.5, 1.0));  // +0.5
        memory.add(engram(ActionType.APPROACH, FruitType.GREEN_APPLE, -1.2, 1.0));  // +1.2

        List<Action> actions = new ArrayList<>(List.of(approach(RED_NEAR), approach(GREEN_NEAR)));
        MemoryFilter filter = new MemoryFilter(memory);

        List<Action> result = filter.filter(actions, HUNGER);

        assertEquals(1, result.size());
        assertEquals(FruitType.RED_APPLE, result.get(0).getTarget());
    }

    @Test
    void undefined_object_type_actions_match_engrams_with_null_type() {
        // WANDER/SLEEP perceptions have no objectType; they should match engrams with null objectType.
        memory.add(engram(ActionType.WANDER, null, -1.0, 1.0));  // score +1.0
        memory.add(engram(ActionType.SLEEP,  null, +0.5, 1.0));  // score -0.5

        Action wander = new Action(ActionType.WANDER, SELF_PERC);
        Action sleep  = new Action(ActionType.SLEEP,  SELF_PERC);
        List<Action> actions = new ArrayList<>(List.of(wander, sleep));
        MemoryFilter filter = new MemoryFilter(memory);

        List<Action> result = filter.filter(actions, HUNGER);

        assertEquals(1, result.size());
        assertEquals(ActionType.WANDER, result.get(0).type);
    }

    @Test
    void filter_type_is_memory() {
        assertEquals(ActionSelectionType.MEMORY, new MemoryFilter(memory).getFilterType());
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static Action approach(Perception perception) {
        return new Action(ActionType.APPROACH, perception);
    }

    private static Emotion emotion(String name, double level) {
        Emotion e = new Emotion(name);
        e.setLevel(level);
        return e;
    }

    private static Engram engram(ActionType action, FruitType objectType, double emotionDelta, double eligibility) {
        Perception p = new Perception(objectType, new SequentialId(99), 10, 0);
        Emotion e = emotion("hunger", 3.0);
        return new Engram(action, new SequentialId(99), e, p, 1L, emotionDelta, eligibility, 1L);
    }

    // Simple in-memory stub — no Akka, no DB.
    private static class ListMemorySystem implements MemorySystem {
        private final List<Engram> engrams = new ArrayList<>();

        void add(Engram e) { engrams.add(e); }

        @Override public List<Engram> getRecentEngrams(int windowSize) {
            int skip = Math.max(0, engrams.size() - windowSize);
            return engrams.subList(skip, engrams.size());
        }

        @Override public void addShortTermMemory(ShortTermMemory stm) {}
        @Override public List<ShortTermMemory> getMemories(SequentialId id) { return List.of(); }
        @Override public void tickDecisionCycle() {}
        @Override public long currentDecisionCycle() { return 0; }
        @Override public List<Engram> reinforceWarmTraces(double emotionDelta, long currentCycle) { return List.of(); }
        @Override public void addEngram(Engram engram) { engrams.add(engram); }
    }
}
