package br.cefetmg.lsi.l2l.creature.arbiter;

/**
 * A controller's belief about one candidate, as in the uncertainty arbitration paper
 * (section 4.4).
 *
 * <ul>
 *   <li>{@code q}: expected value {@code V(K | H)} of the candidate, as computed by
 *       {@link HomeostaticValue#value}. May be negative when the outcome is harmful.</li>
 *   <li>{@code se}: uncertainty about {@code q}. It shrinks as the controller gathers evidence.</li>
 *   <li>{@code sigma}: spread of the outcome itself. It does <em>not</em> shrink with evidence:
 *       more observations pin down {@code q} but leave the outcome as noisy as it was.</li>
 * </ul>
 *
 * <p>Immutable. Non-finite values and negative spreads are rejected at construction, so a NaN
 * never reaches the arbiter's draw silently.
 *
 * @param q     expected value {@code V(K | H)}; finite, may be negative
 * @param se    standard error of {@code q}; finite and non-negative
 * @param sigma outcome spread; finite and non-negative
 * @param basis where the number comes from; see {@link Basis}
 * @param nEff  effective number of neighbours behind the estimate (episodic controller, #113); {@code NaN} when the
 *              controller has no such notion (the operant table). The arbiter's novelty bonus and the fixed-order
 *              evidence test read it
 * @throws IllegalArgumentException if {@code q} is not finite, or {@code se} or {@code sigma}
 *                                  is not finite and non-negative, or {@code nEff} is negative or infinite
 */
public record Estimate(double q, double se, double sigma, double nEff, Basis basis) {

    /**
     * Where an estimate comes from (docs/plans/operant-kalman-and-stop-rule.md, section 1). {@code OBSERVED}: the cell's own
     * outcomes (or a controller with no such notion). {@code POOLED_PRIOR}: the cell has no outcome yet and starts from what
     * the same action did on other objects; it counts as evidence, and is tagged so its calibration can be checked apart.
     * {@code ABSTAIN}: the action was never tried on anything, so there is no honest number: {@code q} and {@code sigma} are
     * NaN and {@code se} is infinite, and the arbiter gives the candidate no claim of its own.
     */
    public enum Basis { OBSERVED, POOLED_PRIOR, ABSTAIN }

    private static final Estimate ABSTAINING = new Estimate(Double.NaN, Double.POSITIVE_INFINITY, Double.NaN, Double.NaN, Basis.ABSTAIN);

    /** The estimate of a controller with no opinion on a candidate. */
    public static Estimate abstain() {
        return ABSTAINING;
    }

    /** An estimate without an effective neighbour count. */
    public Estimate(double q, double se, double sigma) {
        this(q, se, sigma, Double.NaN, Basis.OBSERVED);
    }

    /** An observed estimate with an effective neighbour count. */
    public Estimate(double q, double se, double sigma, double nEff) {
        this(q, se, sigma, nEff, Basis.OBSERVED);
    }

    public Estimate {
        if (basis == null) {
            throw new IllegalArgumentException("basis must not be null");
        }
        if (basis == Basis.ABSTAIN) {
            if (!Double.isNaN(q) || se != Double.POSITIVE_INFINITY || !Double.isNaN(sigma) || !Double.isNaN(nEff)) {
                throw new IllegalArgumentException("an abstaining estimate has q NaN, se +Infinity, sigma NaN and no nEff; use Estimate.abstain()");
            }
        } else {
            if (Double.isInfinite(nEff) || nEff < 0) {
                throw new IllegalArgumentException("nEff must be NaN or finite and non-negative: " + nEff);
            }
            if (!Double.isFinite(q)) {
                throw new IllegalArgumentException("q must be finite: " + q);
            }
            if (!Double.isFinite(se) || se < 0) {
                throw new IllegalArgumentException("se must be finite and non-negative: " + se);
            }
            if (!Double.isFinite(sigma) || sigma < 0) {
                throw new IllegalArgumentException("sigma must be finite and non-negative: " + sigma);
            }
        }
    }

    /** True when the controller gave this candidate no number ({@link Basis#ABSTAIN}). */
    public boolean abstains() {
        return basis == Basis.ABSTAIN;
    }

    /**
     * @param factor multiplier for {@code se}; must be finite and non-negative
     * <p>The product {@code se * factor} may overflow to infinity for very large inputs; in that
     * case the constructor's {@link IllegalArgumentException} is thrown.
     *
     * @return a new estimate with the same {@code q} and {@code sigma} and {@code se * factor}; an abstaining estimate is returned unchanged
     * @throws IllegalArgumentException if {@code factor} is not finite and non-negative, or the
     *                                  scaled {@code se} overflows
     */
    public Estimate withSeScaled(double factor) {
        if (!Double.isFinite(factor) || factor < 0) {
            throw new IllegalArgumentException("factor must be finite and non-negative: " + factor);
        }
        if (abstains()) {
            return this;
        }
        return new Estimate(q, se * factor, sigma, nEff, basis);
    }
}
