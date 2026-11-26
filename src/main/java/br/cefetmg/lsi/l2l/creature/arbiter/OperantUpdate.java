package br.cefetmg.lsi.l2l.creature.arbiter;

/**
 * The state of an operant cell right after one sample was recorded into it, so the caller can log learning.
 *
 * @param n        decayed sample count of the cell
 * @param estimate the cell's {@code (Q, SE, sigma)} after the update
 * @param prior    the cell's estimate just before the update (what it predicted for this outcome), which calibration
 *                 needs without reconstructing the drift; {@code null} when the learner does not report it
 */
public record OperantUpdate(double n, Estimate estimate, Estimate prior) {

    /** An update without a reported prior. */
    public OperantUpdate(double n, Estimate estimate) {
        this(n, estimate, null);
    }
}
