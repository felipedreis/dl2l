package br.cefetmg.lsi.l2l.creature.actionSelector;

import br.cefetmg.lsi.l2l.creature.bd.ActionSelectionType;
import br.cefetmg.lsi.l2l.creature.common.Action;
import br.cefetmg.lsi.l2l.creature.common.Perception;
import br.cefetmg.lsi.l2l.creature.components.Emotion;
import br.cefetmg.lsi.l2l.creature.ml.ModelContract;
import br.cefetmg.lsi.l2l.creature.ml.PredictedEmotionalState;
import br.cefetmg.lsi.l2l.creature.ml.WorldModelEngine;
import br.cefetmg.lsi.l2l.world.FruitType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Mode-2 deliberative action filter.
 *
 * Uses the trained world model to score each candidate action by its predicted aversive
 * emotional cost (pain + fear) and returns actions sorted best-first (lowest cost).
 *
 * Three gates ensure the filter never blocks the cognitive cycle:
 *   1. Mode-2 frequency gate — skips when arousal is low or only one option exists.
 *   2. Inference budget gate — skips when candidate count exceeds INFERENCE_BUDGET.
 *   3. OOD confidence gate  — skips when latent prediction error is above threshold.
 *
 * Any gate hit, or any inference failure, returns the input list unchanged (Mode-1).
 */
public class WorldModelFilter implements ActionFilter {

    // Max candidates scored per cycle. Over this, Mode-1 fallback (budget invariant #5).
    static final int INFERENCE_BUDGET = 16;

    // Only deliberate when emotion level exceeds this threshold.
    // Chosen at p75 of trial_5 arousal data (~26% of cycles, n=106k regulation events).
    static final double HIGH_AROUSAL_THRESHOLD = 4.5;

    private final WorldModelEngine engine;
    private final ModelContract contract;

    public WorldModelFilter(WorldModelEngine engine, ModelContract contract) {
        this.engine   = engine;
        this.contract = contract;
    }

    @Override
    public List<Action> filter(List<Action> actions, Emotion toRegulate) {
        // Gate 1 — Mode-2 frequency gate
        if (actions.size() <= 1 || toRegulate.getLevel() < HIGH_AROUSAL_THRESHOLD)
            return actions;

        // Gate 2 — Inference budget
        if (actions.size() > INFERENCE_BUDGET)
            return actions;

        // Gate 3 — OOD confidence gate (prediction_error_monitor.md §Self-disable threshold)
        if (engine.isOodSelfDisabled())
            return actions;

        // Score every candidate
        List<ScoredAction> scored = new ArrayList<>(actions.size());
        for (Action action : actions) {
            float[] features = encodePerception(action.perception);
            PredictedEmotionalState prediction =
                    engine.predictEmotionalCost(features, action.type);
            if (prediction == null)
                return actions;  // inference error → full Mode-1 fallback for this cycle
            scored.add(new ScoredAction(action, engine.aversiveCost(prediction)));
        }

        // Sort ascending: lowest aversive cost is most preferred
        scored.sort(Comparator.comparingDouble(s -> s.cost));
        return scored.stream().map(s -> s.action).collect(Collectors.toList());
    }

    @Override
    public ActionSelectionType getFilterType() {
        return ActionSelectionType.WORLD_MODEL;
    }

    // Encode perception into model_contract.json perception_feature_order:
    //   [distance, angle, sin(angle), type_GRAY_APPLE, type_GREEN_APPLE, type_RED_APPLE]
    private float[] encodePerception(Perception perception) {
        float[] f = new float[contract.inputDim];
        f[0] = (float) perception.distance;
        f[1] = (float) perception.angle;
        f[2] = (float) Math.sin(perception.angle);
        if (perception.objectType.isDefined()) {
            Object type = perception.objectType.get();
            if (type == FruitType.GRAY_APPLE)  f[3] = 1f;
            if (type == FruitType.GREEN_APPLE) f[4] = 1f;
            if (type == FruitType.RED_APPLE)   f[5] = 1f;
        }
        return f;
    }

    private record ScoredAction(Action action, double cost) {}
}
