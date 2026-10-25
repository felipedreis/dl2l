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
 * @throws IllegalArgumentException if {@code q} is not finite, or {@code se} or {@code sigma}
 *                                  is not finite and non-negative
 */
public record Estimate(double q, double se, double sigma) {

    public Estimate {
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

    /**
     * @param factor multiplier for {@code se}; must be finite and non-negative
     * @return a new estimate with the same {@code q} and {@code sigma} and {@code se * factor}
     * @throws IllegalArgumentException if {@code factor} is not finite and non-negative
     */
    public Estimate withSeScaled(double factor) {
        if (!Double.isFinite(factor) || factor < 0) {
            throw new IllegalArgumentException("factor must be finite and non-negative: " + factor);
        }
        return new Estimate(q, se * factor, sigma);
    }
}
