package br.cefetmg.lsi.l2l.creature.arbiter;

/**
 * The realised K of one attributed act (the four-vector of drive changes that the act caused), sent
 * from {@code HomeostaticRegulation} to {@code Valuation}. Baseline drift is not sent. Immutable: the
 * vector is copied.
 *
 * <p>The cycle is not carried: {@code Valuation} stamps each K with the decision cycle at its arrival,
 * which is what the settle rule of {@link EpisodeBuffer} relies on. A cycle stamped at the sender would
 * need a blocking memory call per outcome and would not be authoritative once the channel reorders.
 *
 * @param k realised change per drive, in {@link FocusTracker#DRIVES} order; finite
 */
public record DriveOutcomeMessage(double[] k) {

    public DriveOutcomeMessage {
        if (k == null) {
            throw new IllegalArgumentException("k must not be null");
        }
        k = k.clone();
    }

    @Override
    public double[] k() {
        return k.clone();
    }
}
