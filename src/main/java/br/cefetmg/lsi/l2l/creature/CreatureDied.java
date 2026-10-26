package br.cefetmg.lsi.l2l.creature;

/**
 * Sent to {@code Valuation} when the creature is killed, so the open and held episodes can be closed
 * with the lethal deviation as their last outcome (uncertainty arbitration paper, section 4.2). Immutable:
 * the vector is copied.
 *
 * @param cycle           decision cycle of the death
 * @param lethalDeviation the change per drive that caused the death, in {@code FocusTracker.DRIVES} order
 */
public record CreatureDied(int cycle, double[] lethalDeviation) {

    public CreatureDied {
        if (lethalDeviation == null) {
            throw new IllegalArgumentException("lethalDeviation must not be null");
        }
        lethalDeviation = lethalDeviation.clone();
    }

    @Override
    public double[] lethalDeviation() {
        return lethalDeviation.clone();
    }
}
