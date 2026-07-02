package br.cefetmg.lsi.l2l.creature.ml;

import ai.djl.ndarray.NDArray;

/**
 * Inference strategy for SpeciesModel (single encoder).
 *
 * Predictor: adaptedZ + action
 * Critic:    zNext    + action
 *
 * Internal state is never used.
 */
public class SingleEncoderStrategy implements ModelVariantStrategy {

    @Override
    public boolean requiresInternalState() {
        return false;
    }

    @Override
    public NDArray buildPredictorInput(NDArray adaptedZ, NDArray zInternal) {
        return adaptedZ;
    }

    @Override
    public NDArray buildCriticInput(NDArray zNext, NDArray zInternal) {
        return zNext;
    }

    @Override
    public String variantName() {
        return "single";
    }
}
