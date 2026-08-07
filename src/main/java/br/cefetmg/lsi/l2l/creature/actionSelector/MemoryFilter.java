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
 * Scores each candidate action by the expected emotional outcome derived from the
 * creature's own recent engrams. The score for action a targeting object type t is the
 * MEAN of (-emotionDelta × eligibility) over all engrams matching (a, t) — an average
 * value per remembered occurrence, not a total. A negative emotionDelta means aversive
 * emotion decreased — a beneficial outcome.
 *
 * The mean matters: with a sum (as before issue #88) the score tracked how often an
 * action had been taken rather than how well it turned out, and because acting lays more
 * engrams the filter reinforced its own past choices regardless of outcome. See
 * docs/plans/memoryfilter-mean-not-sum.md.
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
        // Score = MEAN of -emotionDelta × eligibility; higher = better expected outcome.
        //
        // Mean, not sum (issue #88). Summing made the score grow with how OFTEN an action had
        // been taken rather than how good it was, and since taking an action lays more engrams
        // for it the filter was self-reinforcing: whatever got chosen early accumulated the
        // largest total and kept winning, outcome irrelevant. Measured over one real trial's
        // 252,188 engrams, APPROACH totalled 895.9 across 172,088 engrams while EAT totalled
        // 11.5 across 1,016 — so APPROACH won, despite EAT being worth more than twice as much
        // PER OCCURRENCE (0.0113 vs 0.0052). Creatures approached food continuously and rarely
        // ate it; enabling this filter cut feeding 3.6-65x and took mortality from 0% to 100%
        // in two of three arm pairs. Dividing by the count removes the frequency term and
        // restores the intended ordering (EAT > SLEEP > WANDER > APPROACH on that data).
        Map<ActionKey, double[]> totals = new HashMap<>();   // key -> {sum, count}
        for (Engram e : engrams) {
            WorldObjectType objType = e.perception().objectType.getOrElse(null);
            ActionKey key = new ActionKey(e.actionType(), objType);
            double contribution = -e.emotionDelta() * e.eligibility();
            double[] acc = totals.computeIfAbsent(key, k -> new double[2]);
            acc[0] += contribution;
            acc[1] += 1;
        }
        Map<ActionKey, Double> scores = new HashMap<>();
        totals.forEach((key, acc) -> scores.put(key, acc[0] / acc[1]));

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
