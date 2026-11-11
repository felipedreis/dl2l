package br.cefetmg.lsi.l2l.creature.arbiter;

import br.cefetmg.lsi.l2l.creature.common.Action;
import br.cefetmg.lsi.l2l.creature.common.ActionType;
import br.cefetmg.lsi.l2l.world.WorldObjectType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Operant table (habit) of the uncertainty arbitration paper, section 4.4: the cheapest controller, and a basic
 * per-outcome learner like the legacy {@code OperantConditioning} table (docs/plans/arbiter-proposal-review.md).
 *
 * <p>Keeps decayed Welford statistics of the value experienced per cell {@code (object type, action type)}: no
 * drive and no distance in the key. Each sample is the realised drive reduction of one outcome
 * ({@code -arousalVariation}), fed by {@code Valuation} from the same events that reinforce the legacy table.
 * The statistics turn into an {@link Estimate} by the paper's formulas, with {@code kappa = 1} and {@code Q0 = 0}:
 * <ul>
 *   <li>{@code Q = (n * mean + kappa * Q0) / (n + kappa)}: frequency alone never inflates {@code Q};</li>
 *   <li>{@code sigma}: the prior {@code sigma0 = 1} while {@code n <= 1}; from then on the sample variance
 *       {@code s^2} of the cell blended with the prior as worth {@code NU0 = 1} observation:
 *       {@code sigma^2 = (NU0 * sigma0^2 + (n - 1) * s^2) / (NU0 + n - 1)}. The blend equals the prior at
 *       {@code n = 1}, so {@code sigma} has no step as evidence arrives;</li>
 *   <li>{@code SE = sigma / sqrt(n + kappa)}.</li>
 * </ul>
 * An unseen cell gives {@code Estimate(0, 1, 1)}.
 *
 * <p>The blend is a deliberate departure from the paper's {@code sigma = s}. With {@code sigma = s} a
 * constant outcome has zero sample spread from {@code n >= 2}, so {@code SE} collapses to exactly 0:
 * the decisiveness {@code Phi((Q1 - Q2) / sqrt(SE1^2 + SE2^2))} then saturates and calibration's
 * {@code sqrt(SE^2 + sigma^2)} is 0. The blend keeps {@code sigma} (hence {@code SE}) strictly
 * positive and still shrinking with evidence, while converging to {@code s} for a noisy cell.
 *
 * <p>Candidates without an object (WANDER, SLEEP, ...) use the {@code NONE} object bucket; {@code Valuation}
 * never records into it (outcomes on the creature itself are left out, like the legacy table), so it stays at
 * the prior.
 *
 * <p>{@link #estimate} is read-only and returns one estimate per candidate, in order. Its {@code h} and
 * {@code focalDrive} arguments are unused: the table is not conditioned on the drives.
 *
 * <p>Not thread-safe, and no owner is assumed: it lives behind a TypedActor facade in {@code CreatureActor}.
 */
public final class OperantEstimator implements OperantStore {

    /** Per-update forgetting factor of the paper, section 4.4. */
    public static final double DEFAULT_DECAY = 0.995;

    private static final double KAPPA = 1.0;
    private static final double Q0 = 0.0;
    private static final double SIGMA0 = 1.0;
    /** Weight of the prior spread {@link #SIGMA0}, in observations. */
    private static final double NU0 = 1.0;

    /**
     * Cell key. {@code null} object type is the {@code NONE} bucket. The object type is keyed by its
     * {@link WorldObjectType#name()}, not by the instance: some types (e.g. {@code Self}) have no value equality,
     * so keying by instance would never let a cell accumulate from one perception to the next.
     */
    private record Key(String objectType, ActionType actionType) {
    }

    private final WelfordTable<Key> table;

    /** {@link #DEFAULT_DECAY}. */
    public OperantEstimator() {
        this(DEFAULT_DECAY);
    }

    /**
     * @param decay forgetting factor applied to the updated cell only; in (0, 1] ({@code 1} forgets nothing)
     * @throws IllegalArgumentException if {@code decay} is not in (0, 1]
     */
    public OperantEstimator(double decay) {
        this.table = new WelfordTable<>(decay);
    }

    /**
     * Records the value experienced after {@code action} on {@code target}.
     *
     * @param target the object type acted on; {@code null} is the {@code NONE} bucket
     * @return the cell after the update
     * @throws IllegalArgumentException if {@code action} is null or {@code value} is NaN or infinite; the
     *                                  table is then unchanged
     */
    @Override
    public OperantUpdate record(WorldObjectType target, ActionType action, double value) {
        if (action == null) {
            throw new IllegalArgumentException("action must not be null");
        }
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("value must be finite: " + value);
        }
        Key key = keyOf(target, action);
        table.observe(key, value);
        WelfordTable.Stats s = table.stats(key);
        return new OperantUpdate(s.n(), estimateOf(s));
    }

    /** Convenience for the cell an {@link Action} falls in: its target and type. */
    public OperantUpdate record(Action action, double value) {
        if (action == null) {
            throw new IllegalArgumentException("action must not be null");
        }
        return record(targetOf(action), action.type, value);
    }

    /**
     * Read-only.
     *
     * @param candidates candidate actions; may be empty
     * @param h          unused (see the class javadoc)
     * @param focalDrive unused (see the class javadoc)
     * @return one estimate per candidate, in order; unmodifiable
     * @throws IllegalArgumentException if {@code candidates} is null or holds a null action
     */
    @Override
    public List<Estimate> estimate(List<Action> candidates, double[] h, String focalDrive) {
        if (candidates == null) {
            throw new IllegalArgumentException("candidates must not be null");
        }
        List<Estimate> out = new ArrayList<>(candidates.size());
        for (Action action : candidates) {
            out.add(estimateOf(table.stats(keyOf(targetOf(action), action.type))));
        }
        return Collections.unmodifiableList(out);
    }

    private static WorldObjectType targetOf(Action action) {
        if (action == null || action.perception == null) {
            throw new IllegalArgumentException("action and its perception must not be null");
        }
        return action.getTarget();
    }

    private static Key keyOf(WorldObjectType target, ActionType action) {
        return new Key(target == null ? null : target.name(), action);
    }

    private static double blendedSigma(double n, double sampleSd) {
        double variance = (NU0 * SIGMA0 * SIGMA0 + (n - 1) * sampleSd * sampleSd) / (NU0 + n - 1);
        return Math.sqrt(variance);
    }

    private static Estimate estimateOf(WelfordTable.Stats s) {
        double n = s.n();
        double q = (n * s.mean() + KAPPA * Q0) / (n + KAPPA);
        double sigma = n > 1 ? blendedSigma(n, s.sd()) : SIGMA0;
        double se = sigma / Math.sqrt(n + KAPPA);
        return new Estimate(q, se, sigma);
    }
}
