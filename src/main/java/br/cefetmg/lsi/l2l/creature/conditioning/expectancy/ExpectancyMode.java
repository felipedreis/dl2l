package br.cefetmg.lsi.l2l.creature.conditioning.expectancy;

/**
 * Selects which symbolic {@link ExpectancyPredictor} a creature uses. The two variants differ only
 * in the state they condition on — this is the axis the validation experiment compares.
 */
public enum ExpectancyMode {
    /** Key on {@code (dominantDrive, target, action)} — blind to drive magnitude. */
    DISCRETE,
    /** Key additionally on the discretised dominant-drive level — captures "how hungry". */
    CONTINUOUS
}
