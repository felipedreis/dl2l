package br.cefetmg.lsi.l2l.creature.arbiter;

import br.cefetmg.lsi.l2l.common.Constants;

import java.util.Arrays;

/**
 * Homeostatic deficit and the value of a drive outcome, as in the uncertainty arbitration
 * paper (section 4.1).
 *
 * <p>For a drive-level vector {@code H} with set points {@code H*} and weights {@code w}:
 * <ul>
 *   <li>deficit {@code d(H) = || w .* max(0, H - H*) ||_2}, the weighted distance above
 *       the set point;</li>
 *   <li>value {@code V(K | H) = d(H) - d(H + K)}, the deficit removed by an outcome
 *       {@code K} (negative when {@code K} raises a deficit).</li>
 * </ul>
 * Drives are indexed in the order of {@link FocusTracker#DRIVES}. {@code H + K} is not
 * clamped: the {@code max(0, .)} in the deficit already floors it, and clamping the
 * ceiling would hide the value of an outcome that overshoots.
 *
 * <p>Immutable and stateless, so it is safe to share between threads and components.
 */
public final class HomeostaticValue {

    private final double[] setPoint;
    private final double[] weights;

    /**
     * @param setPoint per-drive set point {@code H*}, in {@link FocusTracker#DRIVES} order
     * @param weights  per-drive weight {@code w}, same order and length as {@code setPoint}
     * @throws IllegalArgumentException if the arrays differ in length
     */
    public HomeostaticValue(double[] setPoint, double[] weights) {
        if (setPoint.length != weights.length) {
            throw new IllegalArgumentException(
                    "setPoint has " + setPoint.length + " drives but weights has " + weights.length);
        }
        this.setPoint = setPoint.clone();
        this.weights = weights.clone();
    }

    /** Four drives in {@link FocusTracker#DRIVES} order, shared set point H* = 0.18, unit weights. */
    public static HomeostaticValue defaults() {
        double[] setPoint = new double[FocusTracker.DRIVES.size()];
        double[] weights = new double[FocusTracker.DRIVES.size()];
        Arrays.fill(setPoint, Constants.MIN_AROUSAL_LEVEL);
        Arrays.fill(weights, 1.0);
        return new HomeostaticValue(setPoint, weights);
    }

    /** Number of drives this instance is configured for. */
    public int dimension() {
        return setPoint.length;
    }

    /**
     * @param h drive levels, one per configured drive
     * @return {@code || w .* max(0, h - H*) ||_2}
     * @throws IllegalArgumentException if {@code h} does not match the configured dimension
     */
    public double deficit(double[] h) {
        checkDimension(h, "h");
        double sum = 0;
        for (int i = 0; i < setPoint.length; i++) {
            double excess = Math.max(0, h[i] - setPoint[i]);
            double weighted = weights[i] * excess;
            sum += weighted * weighted;
        }
        return Math.sqrt(sum);
    }

    /**
     * @param k realized per-drive outcome (change in levels), one per configured drive
     * @param h drive levels before the outcome, one per configured drive
     * @return {@code d(h) - d(h + k)}: the deficit removed by {@code k}
     * @throws IllegalArgumentException if either array does not match the configured dimension
     */
    public double value(double[] k, double[] h) {
        checkDimension(k, "k");
        checkDimension(h, "h");
        double[] after = new double[h.length];
        for (int i = 0; i < h.length; i++) {
            after[i] = h[i] + k[i];
        }
        return deficit(h) - deficit(after);
    }

    private void checkDimension(double[] v, String name) {
        if (v.length != setPoint.length) {
            throw new IllegalArgumentException(
                    name + " has " + v.length + " entries but " + setPoint.length + " drives are configured");
        }
    }
}
