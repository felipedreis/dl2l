package br.cefetmg.lsi.l2l.creature.ml;

import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDArrays;
import ai.djl.ndarray.NDList;

/**
 * Inference strategy for InternalPredictorModel.
 *
 * The Predictor is conditioned on homeostatic urgency; the Critic evaluates
 * the predicted next state using world-space only:
 *
 * Predictor: concat(adaptedZ, zInternal) + action
 * Critic:    zNext                        + action   (world-only input)
 */
public class InternalPredictorStrategy implements ModelVariantStrategy {

    @Override
    public boolean requiresInternalState() {
        return true;
    }

    @Override
    public NDArray buildPredictorInput(NDArray adaptedZ, NDArray zInternal) {
        return NDArrays.concat(new NDList(adaptedZ, zInternal), -1);
    }

    @Override
    public NDArray buildCriticInput(NDArray zNext, NDArray zInternal) {
        return zNext;   // critic is blind to internal state
    }

    @Override
    public String variantName() {
        return "internal_predictor";
    }
}
