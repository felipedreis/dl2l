package br.cefetmg.lsi.l2l.creature.actionSelector;

import br.cefetmg.lsi.l2l.common.Constants;
import br.cefetmg.lsi.l2l.creature.bd.ActionSelectionType;
import br.cefetmg.lsi.l2l.creature.common.Action;
import br.cefetmg.lsi.l2l.creature.common.ActionType;
import br.cefetmg.lsi.l2l.creature.components.Emotion;
import br.cefetmg.lsi.l2l.creature.memory.Engram;
import br.cefetmg.lsi.l2l.creature.memory.MemorySystem;
import br.cefetmg.lsi.l2l.world.WorldObjectType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Symbolic action filter based on Suelen Mapa's long-term memory system (2009).
 *
 * Scores each candidate action by the cumulative expected emotional outcome derived
 * from the creature's own recent engrams. The score for action a targeting object
 * type t is: sum(-emotionDelta × eligibility) over all engrams matching (a, t).
 * A negative emotionDelta means aversive emotion decreased — a beneficial outcome.
 *
 * Decision rule:
 *   - If any action has a non-zero score, return the single highest-scoring action.
 *   - If no action matches any engram, return the full list unchanged (pass-through).
 *
 * Gates:
 *   1. Empty engram buffer → pass-through.
 *   2. Single candidate → pass-through (nothing to disambiguate).
 */
public class MemoryFilter implements ActionFilter {

    private final MemorySystem memory;

    public MemoryFilter(MemorySystem memory) {
        this.memory = memory;
    }

    @Override
    public List<Action> filter(List<Action> actions, Emotion toRegulate) {
        // Gate 1 — nothing to score
        if (actions.size() <= 1) return actions;

        List<Engram> engrams = memory.getRecentEngrams(Constants.MEMORY_FILTER_WINDOW);

        // Gate 2 — no experience yet
        if (engrams.isEmpty()) return actions;

        // Accumulate score per (ActionType, WorldObjectType) key from engrams.
        // Score = sum of -emotionDelta × eligibility; higher = better expected outcome.
        Map<ActionKey, Double> scores = new HashMap<>();
        for (Engram e : engrams) {
            WorldObjectType objType = e.perception().objectType.getOrElse(null);
            ActionKey key = new ActionKey(e.actionType(), objType);
            double contribution = -e.emotionDelta() * e.eligibility();
            scores.merge(key, contribution, Double::sum);
        }

        // Split candidates into scored and unscored buckets.
        List<ScoredAction> scored = new ArrayList<>();
        List<Action> unscored = new ArrayList<>();

        for (Action a : actions) {
            WorldObjectType objType = a.perception.objectType.getOrElse(null);
            ActionKey key = new ActionKey(a.type, objType);
            Double s = scores.get(key);
            if (s != null) {
                scored.add(new ScoredAction(a, s));
            } else {
                unscored.add(a);
            }
        }

        if (scored.isEmpty()) return actions;

        scored.sort(Comparator.comparingDouble(sa -> -sa.score));
        return List.of(scored.get(0).action);
    }

    @Override
    public ActionSelectionType getFilterType() {
        return ActionSelectionType.MEMORY;
    }

    private record ActionKey(ActionType actionType, WorldObjectType objectType) {
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof ActionKey other)) return false;
            return actionType == other.actionType && Objects.equals(objectType, other.objectType);
        }

        @Override
        public int hashCode() {
            return Objects.hash(actionType, objectType);
        }
    }

    private record ScoredAction(Action action, double score) {}
}
