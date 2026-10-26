package br.cefetmg.lsi.l2l.creature.arbiter;

import br.cefetmg.lsi.l2l.common.Constants;
import br.cefetmg.lsi.l2l.creature.common.Action;
import br.cefetmg.lsi.l2l.creature.common.ActionType;
import br.cefetmg.lsi.l2l.world.WorldObjectType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

/**
 * Operant table (habit) of the uncertainty arbitration paper, section 4.4: the cheapest controller.
 *
 * <p>Keeps decayed Welford statistics of the value experienced per cell
 * {@code (focal drive, object type, distance bin, action type)} and turns them into an
 * {@link Estimate} by the paper's formulas, with {@code kappa = 1} and {@code Q0 = 0}:
 * <ul>
 *   <li>{@code Q = (n * mean + kappa * Q0) / (n + kappa)}: frequency alone never inflates {@code Q};</li>
 *   <li>{@code sigma}: the prior {@code sigma0 = 1} while {@code n < 3}; from then on the sample variance
 *       {@code s^2} of the cell blended with the prior as worth {@code NU0 = 1} observation:
 *       {@code sigma^2 = (NU0 * sigma0^2 + (n - 1) * s^2) / (NU0 + n - 1)};</li>
 *   <li>{@code SE = sigma / sqrt(n + kappa)}.</li>
 * </ul>
 * An unseen cell gives {@code Estimate(0, 1, 1)}.
 *
 * <p>The blend is a deliberate departure from the paper's {@code sigma = s}. With {@code sigma = s} a
 * constant outcome has zero sample spread from {@code n >= 3}, so {@code SE} collapses to exactly 0:
 * the decisiveness {@code Phi((Q1 - Q2) / sqrt(SE1^2 + SE2^2))} then saturates and calibration's
 * {@code sqrt(SE^2 + sigma^2)} is 0. The blend keeps {@code sigma} (hence {@code SE}) strictly
 * positive and still shrinking with evidence, while converging to {@code s} for a noisy cell.
 *
 * <p>Binning: the distance bins are the intervals between the sorted upper edges given at
 * construction, with each edge inclusive on its lower bin. The default has three bins at one third
 * and two thirds of {@link Constants#DEFAULT_VISION_FIELD_RADIUS}. Candidates without an object
 * (WANDER, SLEEP, ...) use the {@code NONE} object bucket and bin 0; a {@code null} focal drive is
 * the {@code NONE} focal bucket.
 *
 * <p>{@link #estimate} is read-only and returns one estimate per candidate, in order. Its
 * {@code h} argument is unused: the value was already evaluated against H when it was recorded.
 *
 * <p>Not thread-safe, and no owner is assumed: where this instance lives and who writes to it is
 * decided by the wiring issues, not here. Each creature's estimator should be its own actor's state.
 */
public final class OperantEstimator implements ValueEstimator, ValueLearner {

    /** Per-update forgetting factor of the paper, section 4.4. */
    public static final double DEFAULT_DECAY = 0.995;

    private static final double KAPPA = 1.0;
    private static final double Q0 = 0.0;
    private static final double SIGMA0 = 1.0;
    private static final int MIN_N_FOR_SAMPLE_SD = 3;
    /** Weight of the prior spread {@link #SIGMA0}, in observations. */
    private static final double NU0 = 1.0;

    /** Cell key. {@code null} in a field means that bucket is {@code NONE}. */
    private record Key(String focalDrive, WorldObjectType objectType, int distanceBin, ActionType actionType) {
    }

    private final Supplier<String> focalDrive;
    private final double[] distanceEdges;
    private final WelfordTable<Key> table;

    /**
     * Default binning ({@code DEFAULT_VISION_FIELD_RADIUS / 3} and {@code 2 * .../3}) and
     * {@link #DEFAULT_DECAY}.
     *
     * @param focalDrive supplies the focal drive at estimate time; may return {@code null}
     * @throws IllegalArgumentException if {@code focalDrive} is null
     */
    public OperantEstimator(Supplier<String> focalDrive) {
        this(focalDrive, defaultDistanceEdges(), DEFAULT_DECAY);
    }

    /**
     * @param focalDrive    supplies the focal drive at estimate time; may return {@code null}
     * @param distanceEdges strictly increasing, finite upper edges of the distance bins; empty for one bin
     * @param decay         forgetting factor applied to the updated cell only; in (0, 1]
     *                      ({@code 1} forgets nothing)
     * @throws IllegalArgumentException if {@code focalDrive} or {@code distanceEdges} is null, an edge
     *                                  is not finite or the edges are not strictly increasing, or
     *                                  {@code decay} is not in (0, 1]
     */
    public OperantEstimator(Supplier<String> focalDrive, double[] distanceEdges, double decay) {
        if (focalDrive == null) {
            throw new IllegalArgumentException("focalDrive supplier must not be null");
        }
        if (distanceEdges == null) {
            throw new IllegalArgumentException("distanceEdges must not be null");
        }
        for (int i = 0; i < distanceEdges.length; i++) {
            if (!Double.isFinite(distanceEdges[i])) {
                throw new IllegalArgumentException("distanceEdges[" + i + "] is not finite: " + distanceEdges[i]);
            }
            if (i > 0 && distanceEdges[i] <= distanceEdges[i - 1]) {
                throw new IllegalArgumentException("distanceEdges must be strictly increasing at index " + i);
            }
        }
        this.focalDrive = focalDrive;
        this.distanceEdges = distanceEdges.clone();
        this.table = new WelfordTable<>(decay);
    }

    private static double[] defaultDistanceEdges() {
        double radius = Constants.DEFAULT_VISION_FIELD_RADIUS;
        return new double[] {radius / 3, 2 * radius / 3};
    }

    /**
     * Records the value experienced after {@code action}, in the cell of the given focal drive.
     *
     * @throws IllegalArgumentException if {@code action} (or its perception) is null, or
     *                                  {@code value} is NaN or infinite; the table is then unchanged
     */
    @Override
    public void record(String focalDrive, Action action, double value) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("value must be finite: " + value);
        }
        table.observe(keyOf(focalDrive, action), value);
    }

    /**
     * Read-only. The focal drive is read once per call from the supplier.
     *
     * @param candidates candidate actions; may be empty
     * @param h          unused by this estimator (see the class javadoc)
     * @return one estimate per candidate, in order; unmodifiable
     * @throws IllegalArgumentException if {@code candidates} is null or holds a null action
     */
    @Override
    public List<Estimate> estimate(List<Action> candidates, double[] h) {
        if (candidates == null) {
            throw new IllegalArgumentException("candidates must not be null");
        }
        String drive = focalDrive.get();
        List<Estimate> out = new ArrayList<>(candidates.size());
        for (Action action : candidates) {
            out.add(estimateOf(table.stats(keyOf(drive, action))));
        }
        return Collections.unmodifiableList(out);
    }

    private Key keyOf(String drive, Action action) {
        if (action == null || action.perception == null) {
            throw new IllegalArgumentException("action and its perception must not be null");
        }
        WorldObjectType target = action.getTarget();
        if (target == null) {
            return new Key(drive, null, 0, action.type);
        }
        double distance = action.perception.distance;
        if (!Double.isFinite(distance)) {
            throw new IllegalArgumentException("perception distance must be finite: " + distance);
        }
        return new Key(drive, target, distanceBin(distance), action.type);
    }

    /** Bin {@code i} holds distances in {@code (edge[i-1], edge[i]]}; the last bin is open-ended. */
    private int distanceBin(double distance) {
        int bin = 0;
        while (bin < distanceEdges.length && distance > distanceEdges[bin]) {
            bin++;
        }
        return bin;
    }

    private static double blendedSigma(double n, double sampleSd) {
        double variance = (NU0 * SIGMA0 * SIGMA0 + (n - 1) * sampleSd * sampleSd) / (NU0 + n - 1);
        return Math.sqrt(variance);
    }

    private static Estimate estimateOf(WelfordTable.Stats s) {
        double n = s.n();
        double q = (n * s.mean() + KAPPA * Q0) / (n + KAPPA);
        double sigma = n >= MIN_N_FOR_SAMPLE_SD ? blendedSigma(n, s.sd()) : SIGMA0;
        double se = sigma / Math.sqrt(n + KAPPA);
        return new Estimate(q, se, sigma);
    }
}
