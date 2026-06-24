package br.cefetmg.lsi.l2l.creature.ml;

import ai.djl.inference.Predictor;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.NDManager;
import ai.djl.translate.TranslateException;
import br.cefetmg.lsi.l2l.common.Constants;
import br.cefetmg.lsi.l2l.creature.common.ActionType;

import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Per-creature synchronous inference pipeline: encoder → per-creature adapter → predictor → critic.
 *
 * Owned by FullAppraisal and used exclusively on the creature actor thread during waking.
 * The adapter Predictor is opened from the same ZooModel that MemoryConsolidator trains
 * during sleep — training updates are visible here after the next wake-up (temporal separation
 * enforced by the sleep/wake state machine + MemoryConsolidator.abortFlag).
 *
 * Call close() in FullAppraisal.postStop() to release native Predictor handles.
 */
public class WorldModelEngine implements AutoCloseable {

    private static final Logger logger = Logger.getLogger(WorldModelEngine.class.getName());

    // Must match model_contract.json action_index_order (alphabetical).
    private static final ActionType[] ACTION_ORDER = {
            ActionType.APPROACH, ActionType.AVOID, ActionType.EAT, ActionType.ESCAPE,
            ActionType.PLAY, ActionType.SLEEP, ActionType.TOUCH, ActionType.TURN, ActionType.WANDER
    };

    // Aversive dimensions per model_contract.json emotion_index_order.
    // pain = index 4, fear = index 6.
    private static final Set<String> AVERSIVE_DIMS = Set.of(Constants.PAIN, Constants.FEAR);

    private final Predictor<NDList, NDList> encoderPredictor;
    private final Predictor<NDList, NDList> adapterPredictor;
    private final Predictor<NDList, NDList> predictorPredictor;
    private final Predictor<NDList, NDList> criticPredictor;
    private final ModelContract contract;

    // OOD gating — rolling EMA of latent prediction error (prediction_error_monitor.md).
    private double latentPredErrorEma;
    private final double emaAlpha;  // 2 / (N + 1), N = 100 engrams

    public WorldModelEngine(MLServiceExtension.Impl mlExt, long creatureKey) {
        this.contract = mlExt.models().contract();

        // Validate action index order at construction — fail fast on contract mismatch.
        if (contract.actionIndexOrder == null || contract.actionIndexOrder.size() != ACTION_ORDER.length)
            throw new IllegalStateException("model_contract action_index_order missing or wrong size");
        for (int i = 0; i < ACTION_ORDER.length; i++) {
            String expected = contract.actionIndexOrder.get(i);
            if (!ACTION_ORDER[i].name().equals(expected))
                throw new IllegalStateException(
                        "Action index mismatch at " + i + ": contract says '" + expected
                        + "' but ACTION_ORDER has '" + ACTION_ORDER[i].name() + "'");
        }

        MLServiceExtension.LoadedModels m = mlExt.models();
        this.encoderPredictor   = m.encoder().newPredictor();
        this.adapterPredictor   = mlExt.getOrCreateAdapter(creatureKey).newPredictor();
        this.predictorPredictor = m.predictor().newPredictor();
        this.criticPredictor    = m.critic().newPredictor();

        this.latentPredErrorEma = contract.baselinePredError;
        this.emaAlpha           = 2.0 / (100 + 1);
    }

    /**
     * Synchronous inference: perception → encoder → adapter → predictor(action) → critic.
     * Returns null on TranslateException; callers treat null as Mode-1 fallback for the cycle.
     *
     * @param perceptionFeatures float[inputDim] encoded per model_contract perception_feature_order
     * @param actionType         the candidate action to score
     */
    public PredictedEmotionalState predictEmotionalCost(float[] perceptionFeatures,
                                                         ActionType actionType) {
        try (NDManager mgr = NDManager.newBaseManager()) {
            NDArray perc       = mgr.create(perceptionFeatures);
            NDArray latent     = encoderPredictor.predict(new NDList(perc)).singletonOrThrow();
            NDArray adapted    = adapterPredictor.predict(new NDList(latent)).singletonOrThrow();
            NDArray actionHot  = mgr.create(encodeAction(actionType));
            NDArray nextLatent = predictorPredictor.predict(
                                     new NDList(adapted, actionHot)).singletonOrThrow();
            float[] deltas     = criticPredictor.predict(
                                     new NDList(nextLatent, actionHot))
                                     .singletonOrThrow().toFloatArray();
            return buildState(deltas);
        } catch (TranslateException e) {
            logger.log(Level.WARNING, "WorldModelEngine: inference failed, returning null", e);
            return null;
        }
    }

    /**
     * Aversive cost = sum of predicted levels for AVERSIVE_DIMS (pain + fear).
     * Lower is better; used by WorldModelFilter to rank candidates.
     */
    public double aversiveCost(PredictedEmotionalState predicted) {
        double cost = 0;
        for (String dim : AVERSIVE_DIMS)
            cost += predicted.level(contract.emotionIndexOf(dim));
        return cost;
    }

    /**
     * True when the OOD EMA exceeds the self-disable threshold from prediction_error_monitor.md.
     * When true, WorldModelFilter returns its input unchanged (Mode-1 fallback for the cycle).
     */
    public boolean isOodSelfDisabled() {
        return latentPredErrorEma > contract.oodThresholdMultiplier * contract.baselinePredError;
    }

    @Override
    public void close() {
        closeSilently(encoderPredictor);
        closeSilently(adapterPredictor);
        closeSilently(predictorPredictor);
        closeSilently(criticPredictor);
    }

    // -----------------------------------------------------------------------

    private float[] encodeAction(ActionType action) {
        float[] hot = new float[contract.actionDim];
        for (int i = 0; i < ACTION_ORDER.length; i++) {
            if (ACTION_ORDER[i] == action) {
                hot[i] = 1f;
                break;
            }
        }
        return hot;
    }

    private PredictedEmotionalState buildState(float[] deltas) {
        double[] levels = new double[contract.emotionDim];
        double range = contract.maxArousal - contract.minArousal;
        for (int i = 0; i < contract.emotionDim && i < deltas.length; i++) {
            // Critic output is in [-1, 1] (tanh-bounded during training).
            // Map linearly to [minArousal, maxArousal].
            double normalised = (deltas[i] + 1.0) / 2.0;
            double level = contract.minArousal + normalised * range;
            levels[i] = Math.max(contract.minArousal, Math.min(contract.maxArousal, level));
        }
        return new PredictedEmotionalState(levels);
    }

    private static void closeSilently(AutoCloseable c) {
        if (c != null) try { c.close(); } catch (Exception ignored) {}
    }
}
