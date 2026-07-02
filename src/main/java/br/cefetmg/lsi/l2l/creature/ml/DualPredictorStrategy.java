package br.cefetmg.lsi.l2l.creature.ml;

import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDArrays;
import ai.djl.ndarray.NDList;

/**
 * Inference strategy for DualSpeciesModel.
 *
 * Internal state informs both the Predictor and the Critic:
 *
 * Predictor: concat(adaptedZ, zInternal) + action
 * Critic:    concat(zNext,    zInternal) + action
 */
public class DualPredictorStrategy implements ModelVariantStrategy {

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
        return NDArrays.concat(new NDList(zNext, zInternal), -1);
    }

    @Override
    public String variantName() {
        return "dual";
    }
}
