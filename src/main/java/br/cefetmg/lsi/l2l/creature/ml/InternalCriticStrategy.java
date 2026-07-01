package br.cefetmg.lsi.l2l.creature.ml;

import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDArrays;
import ai.djl.ndarray.NDList;

/**
 * Inference strategy for InternalCriticModel.
 *
 * The Predictor is a pure world-dynamics model (blind to internal state).
 * Only the Critic sees the creature's homeostatic urgency:
 *
 * Predictor: adaptedZ           + action   (world-only input)
 * Critic:    concat(zNext, zInternal) + action
 */
public class InternalCriticStrategy implements ModelVariantStrategy {

    @Override
    public boolean requiresInternalState() {
        return true;
    }

    @Override
    public NDArray buildPredictorInput(NDArray adaptedZ, NDArray zInternal) {
        return adaptedZ;   // predictor is blind to internal state
    }

    @Override
    public NDArray buildCriticInput(NDArray zNext, NDArray zInternal) {
        return NDArrays.concat(new NDList(zNext, zInternal), -1);
    }

    @Override
    public String variantName() {
        return "internal_critic";
    }
}
