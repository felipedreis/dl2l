package br.cefetmg.lsi.l2l.creature.ml;

import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDArrays;
import ai.djl.ndarray.NDList;

/**
 * Strategy that encapsulates the tensor-routing differences between model variants.
 *
 * Each variant feeds z_world, z_internal, and z_next to the Predictor and Critic
 * in different ways:
 *
 *   single              — Predictor(z_world, a),               Critic(z_next, a)
 *   dual                — Predictor(concat(z_world, z_internal), a), Critic(concat(z_next, z_internal), a)
 *   internal_critic     — Predictor(z_world, a),               Critic(concat(z_next, z_internal), a)
 *   internal_predictor  — Predictor(concat(z_world, z_internal), a), Critic(z_next, a)
 *
 * Both WorldModelEngine (inference) and MemoryConsolidator (sleep training) delegate
 * tensor construction to this strategy so the routing is defined exactly once per variant.
 */
public interface ModelVariantStrategy {

    /** True when this variant requires a non-null internal state (h_t) input. */
    boolean requiresInternalState();

    /**
     * Build the latent input to the Predictor.
     *
     * @param adaptedZ   world latent after the per-creature adapter [batch, latentDim]
     * @param zInternal  internal encoder output [batch, internalLatentDim]; null for single-encoder
     * @return tensor passed (alongside action one-hot) to the Predictor
     */
    NDArray buildPredictorInput(NDArray adaptedZ, NDArray zInternal);

    /**
     * Build the latent input to the Critic.
     *
     * @param zNext      predicted next latent from the Predictor [batch, latentDim]
     * @param zInternal  internal encoder output [batch, internalLatentDim]; null for single-encoder
     * @return tensor passed (alongside action one-hot) to the Critic
     */
    NDArray buildCriticInput(NDArray zNext, NDArray zInternal);

    /** The model_variant string as it appears in model_contract.json. */
    String variantName();
}
