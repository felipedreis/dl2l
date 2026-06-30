package br.cefetmg.lsi.l2l.creature.actionSelector;

import br.cefetmg.lsi.l2l.creature.bd.ActionSelectionType;
import br.cefetmg.lsi.l2l.creature.common.Action;
import br.cefetmg.lsi.l2l.creature.common.ActionType;
import br.cefetmg.lsi.l2l.creature.common.Perception;
import br.cefetmg.lsi.l2l.creature.components.Emotion;
import br.cefetmg.lsi.l2l.creature.ml.ModelContract;
import br.cefetmg.lsi.l2l.creature.ml.PredictedEmotionalState;
import br.cefetmg.lsi.l2l.creature.ml.WorldModelEngine;
import br.cefetmg.lsi.l2l.world.FruitType;
import br.cefetmg.lsi.l2l.world.PlantType;

import java.util.EnumSet;
import java.util.Set;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

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

    // Exploratory / idle actions never appear in Mode-2 training data and the Predictor
    // extrapolates their z_next OOD, causing the Critic to assign spuriously negative
    // (good) scores. Exclude them from scoring so they fall through to Mode-1 filters.
    private static final Set<ActionType> MODE_1_ONLY = EnumSet.of(ActionType.WANDER, ActionType.OBSERVE);

    private final WorldModelEngine engine;
    private final ModelContract contract;

    // Current live homeostatic state — set each cycle by FullAppraisal before calling filter().
    // Null when running with a single-encoder model (contract.hasDualEncoder == false).
    private float[] currentInternalState;

    public WorldModelFilter(WorldModelEngine engine, ModelContract contract) {
        this.engine   = engine;
        this.contract = contract;
    }

    /**
     * Called by FullAppraisal each cognitive cycle before filter() so the dual-encoder
     * path has the creature's current homeostatic state available at inference time.
     *
     * @param internalState float[internalStateDim] in model_contract internal_state_feature_order
     *                      (hunger, sleep, pain, tedium). Ignored when hasDualEncoder=false.
     */
    public void updateInternalState(float[] internalState) {
        this.currentInternalState = internalState;
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

        // Score every need-driven candidate; skip exploratory actions (OOD for the Critic)
        List<ScoredAction> scored = new ArrayList<>(actions.size());
        for (Action action : actions) {
            if (MODE_1_ONLY.contains(action.type))
                continue;
            float[] features = encodePerception(action.perception);
            PredictedEmotionalState prediction = contract.hasDualEncoder
                    ? engine.predictEmotionalCost(features, currentInternalState, action.type)
                    : engine.predictEmotionalCost(features, action.type);
            if (prediction == null)
                return actions;  // inference error → full Mode-1 fallback for this cycle
            scored.add(new ScoredAction(action, engine.aversiveCost(prediction)));
        }

        // If no need-driven action is available, fall back to Mode-1.
        if (scored.isEmpty())
            return actions;

        // Return only the best-predicted action so ActionSelection records WORLD_MODEL
        // as the deciding filter and RandomFilter is bypassed for this cycle.
        scored.sort(Comparator.comparingDouble(s -> s.cost));
        return List.of(scored.get(0).action);
    }

    @Override
    public ActionSelectionType getFilterType() {
        return ActionSelectionType.WORLD_MODEL;
    }

    // Encode perception into model_contract.json perception_feature_order:
    //   [distance, angle, direction, type_GRAY_APPLE, type_GREEN_APPLE, type_RED_APPLE,
    //    type_ROTTEN_APPLE, type_ALOE, type_CACTUS]
    // Index positions are driven by contract.perceptionFeatureOrder to stay in sync
    // with whatever object types were present during training.
    private float[] encodePerception(Perception perception) {
        float[] f = new float[contract.inputDim];
        f[0] = (float) perception.distance;
        f[1] = (float) perception.angle;
        f[2] = (float) Math.sin(perception.angle);
        if (perception.objectType.isDefined()) {
            Object type = perception.objectType.get();
            String typeName = "type_" + (type instanceof FruitType ft ? ft.name()
                                       : type instanceof PlantType pt ? pt.name() : "");
            List<String> order = contract.perceptionFeatureOrder;
            int idx = order.indexOf(typeName);
            if (idx >= 0 && idx < f.length) f[idx] = 1f;
        }
        return f;
    }

    private record ScoredAction(Action action, double cost) {}
}
