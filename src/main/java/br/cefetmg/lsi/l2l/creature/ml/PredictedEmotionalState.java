package br.cefetmg.lsi.l2l.creature.ml;

/**
 * Lightweight, immutable holder for the critic's per-dimension emotion-delta predictions.
 * Indices correspond to model_contract.json emotion_index_order.
 * Not a JPA entity — only used in-memory during action scoring.
 */
public record PredictedEmotionalState(double[] levels) {

    public double level(int index) {
        return levels[index];
    }
}
