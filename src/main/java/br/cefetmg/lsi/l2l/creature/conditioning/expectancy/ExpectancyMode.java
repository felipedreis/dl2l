package br.cefetmg.lsi.l2l.creature.conditioning.expectancy;

/**
 * Selects which {@link ExpectancyPredictor} a creature uses.
 */
public enum ExpectancyMode {
    /** Key on {@code (dominantDrive, target, action)} — blind to drive magnitude. */
    DISCRETE,
    /** Key additionally on the discretised dominant-drive level — captures "how hungry". */
    CONTINUOUS,
    /**
     * JEPA emotion head as the RPE baseline: {@code expected = −aversiveCost(JEPA_prediction)}.
     * Dopamine fires when reality deviates from what the world model predicted, not from the
     * historical mean. Requires {@code WORLD_MODEL} filter to be enabled; falls back to 0.0
     * (neutral prior) on gated cycles.
     */
    JEPA
}
