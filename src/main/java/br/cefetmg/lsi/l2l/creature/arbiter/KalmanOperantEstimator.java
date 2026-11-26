package br.cefetmg.lsi.l2l.creature.arbiter;

import br.cefetmg.lsi.l2l.creature.common.Action;
import br.cefetmg.lsi.l2l.creature.common.ActionType;
import br.cefetmg.lsi.l2l.world.WorldObjectType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Operant table as one Kalman filter per cell {@code (object type, action type)}
 * (docs/plans/operant-kalman-and-stop-rule.md, section 1; literature: docs/plans/controllers-literature-operant-learner.md).
 *
 * <p>A Kalman filter here is a running average whose step size depends on how unsure it is, and whose uncertainty creeps
 * back up when nothing is observed, because the world may have changed. Per cell: {@code m}, the expected drive reduction of
 * the action on that object; {@code P}, the variance of {@code m} (SE = {@code sqrt(P)}); and the spread of single outcomes
 * {@code sigma}, from the decayed Welford statistics of the cell blended with a prior worth one observation.
 *
 * <p>On an outcome {@code v} of cell {@code (o, a)}: {@code P += tau2; k = P / (P + s2); m += k (v - m); P *= (1 - k)}, and
 * {@code P += tau2} on every other existing cell of the same object (the clock ticks once per outcome on that object, by any action).
 * {@code tau2} sets the steady-state gain {@code k*}: {@code tau2 = k*^2 s2 / (1 - k*)}.
 *
 * <p>A cell with no outcome of its own starts from what the creature knows about that action on other objects (mean of their
 * {@code m}; variance {@code max(between-object variance, poolFloor) + tau2}), tagged {@link Estimate.Basis#POOLED_PRIOR}. An action that
 * was never tried on anything abstains ({@link Estimate#abstain()}); its first outcome gives {@code m = v, P = s2}. APPROACH, and the
 * {@code NONE} bucket (WANDER, SLEEP on the creature), never receive an outcome ({@code Valuation} records only outcomes on fruit and
 * plants), so they abstain by construction; nothing here special-cases them.
 *
 * <p>The sample is a drive reduction in the common unit (the arbiter's {@code V(K | H)}), not a valence, so the numbers are
 * comparable with the episodic controller's. Scale: a bite is worth about 0.15 to 0.4, hence {@code s2_0 = 0.1^2}.
 *
 * <p>Not thread-safe; it lives behind a TypedActor facade in {@code CreatureActor}, like {@link OperantEstimator}.
 */
public final class KalmanOperantEstimator implements OperantStore {

    /**
     * Drift per outcome on the object, fixed on the replay of p117_factorial (analysis/diagnostics/kalman_operant_replay.py) and then frozen:
     * the plan's 8e-4 (steady-state gain 0.25) gave a one-step z variance of 0.71 on the varying cells, below the [0.8, 1.25] band (SE too wide);
     * 1e-4 gives 0.81 (steady-state gain about 0.1 at {@code s2 = 0.005}; {@code tau2 = k*^2 s2 / (1 - k*)}). The replay data are stationary,
     * which favours a small drift, so this is the slowest the cell should be, not the right value for a changing world.
     */
    public static final double DEFAULT_TAU2 = 1e-4;
    public static final double DEFAULT_S2_0 = 0.1 * 0.1;
    public static final double DEFAULT_S2_MIN = 0.05 * 0.05;
    /**
     * Floor of the variance of a pooled prior: 16 prior outcome variances (SE 0.4), because objects differ a lot in what the same action does
     * (EAT is worth +0.3 on an apple and -0.7 on a cactus) and the between-object spread of the few cells seen so far understates that. Chosen
     * on the replay of p117_factorial (analysis/diagnostics/kalman_operant_replay.py): with the floor at one outcome variance the first outcome
     * of a new object had z variance 8; at 16 it is 1.3.
     */
    public static final double DEFAULT_POOL_FLOOR = 16 * DEFAULT_S2_0;
    /** Weight of the prior spread, in observations (as {@link OperantEstimator}'s blend). */
    private static final double NU0 = 1.0;

    private record Key(String objectType, ActionType actionType) {
    }

    private static final class Cell {
        double m;
        double p;
        double n;      // observations of this cell, undecayed
        double mean;   // Welford over the samples, for the spread
        double m2;
    }

    private final double tau2;
    private final double s2Prior;
    private final double s2Min;
    private final double decay;
    private final double poolFloor;
    private final Map<Key, Cell> cells = new HashMap<>();

    public KalmanOperantEstimator() {
        this(DEFAULT_TAU2, DEFAULT_S2_0, DEFAULT_S2_MIN, OperantEstimator.DEFAULT_DECAY);
    }

    /** {@link #DEFAULT_POOL_FLOOR} scaled to {@code s2Prior}'s ratio, i.e. 16 prior variances. */
    public KalmanOperantEstimator(double tau2, double s2Prior, double s2Min, double decay) {
        this(tau2, s2Prior, s2Min, decay, 16 * s2Prior);
    }

    /**
     * @param tau2    drift added to a cell's variance per outcome on its object; positive
     * @param s2Prior prior outcome variance, worth one observation; positive
     * @param s2Min   floor of the outcome variance; positive
     * @param decay   forgetting factor of the spread statistics, in (0, 1]
     * @param poolFloor floor of the variance of a pooled prior (before adding {@code tau2}); positive
     */
    public KalmanOperantEstimator(double tau2, double s2Prior, double s2Min, double decay, double poolFloor) {
        if (!(tau2 > 0) || !(s2Prior > 0) || !(s2Min > 0) || !(decay > 0 && decay <= 1)
                || !(poolFloor > 0) || !Double.isFinite(tau2) || !Double.isFinite(s2Prior) || !Double.isFinite(s2Min)
                || !Double.isFinite(poolFloor)) {
            throw new IllegalArgumentException("tau2, s2Prior and s2Min must be finite and positive and decay in (0, 1]");
        }
        this.tau2 = tau2;
        this.s2Prior = s2Prior;
        this.s2Min = s2Min;
        this.decay = decay;
        this.poolFloor = poolFloor;
    }

    @Override
    public OperantUpdate record(WorldObjectType target, ActionType action, double value) {
        if (action == null) {
            throw new IllegalArgumentException("action must not be null");
        }
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("value must be finite: " + value);
        }
        Key key = keyOf(target, action);
        Estimate prior = estimateOf(key);
        // The clock: one outcome on this object, by any action, ages every other cell of the object.
        for (Map.Entry<Key, Cell> e : cells.entrySet()) {
            if (!e.getKey().equals(key) && java.util.Objects.equals(e.getKey().objectType(), key.objectType())) {
                e.getValue().p += tau2;
            }
        }
        Cell c = cells.get(key);
        if (c == null) {
            c = new Cell();
            cells.put(key, c);
            Pool pool = pool(key.actionType(), key);
            if (pool == null) {
                // First outcome of an action never tried on anything: the limit of the update with infinite variance.
                c.m = value;
                c.p = Math.max(s2Prior, s2Min);
                observeSpread(c, value);
                return new OperantUpdate(c.n, estimateOf(key), prior);
            }
            c.m = pool.mean;
            c.p = pool.variance;                     // already includes tau2
        } else {
            c.p += tau2;
        }
        double s2 = spreadVariance(c);
        double k = c.p / (c.p + s2);
        c.m += k * (value - c.m);
        c.p *= (1 - k);
        observeSpread(c, value);
        return new OperantUpdate(c.n, estimateOf(key), prior);
    }

    @Override
    public List<Estimate> estimate(List<Action> candidates, double[] h, String focalDrive) {
        if (candidates == null) {
            throw new IllegalArgumentException("candidates must not be null");
        }
        List<Estimate> out = new ArrayList<>(candidates.size());
        for (Action action : candidates) {
            if (action == null || action.perception == null) {
                throw new IllegalArgumentException("action and its perception must not be null");
            }
            out.add(estimateOf(keyOf(action.getTarget(), action.type)));
        }
        return Collections.unmodifiableList(out);
    }

    /** The estimate of one cell, without changing anything: its own, a pooled prior, or an abstention. */
    private Estimate estimateOf(Key key) {
        Cell c = cells.get(key);
        if (c != null) {
            return new Estimate(c.m, Math.sqrt(c.p), Math.sqrt(spreadVariance(c)));
        }
        Pool pool = pool(key.actionType(), key);
        if (pool == null) {
            return Estimate.abstain();
        }
        return new Estimate(pool.mean, Math.sqrt(pool.variance), Math.sqrt(s2Prior), Double.NaN, Estimate.Basis.POOLED_PRIOR);
    }

    private record Pool(double mean, double variance) {
    }

    /** What the action did on the other objects; {@code null} when it was never tried on anything. */
    private Pool pool(ActionType action, Key except) {
        double sum = 0;
        int n = 0;
        for (Map.Entry<Key, Cell> e : cells.entrySet()) {
            if (e.getKey().actionType() == action && !e.getKey().equals(except)) {
                sum += e.getValue().m;
                n++;
            }
        }
        if (n == 0) {
            return null;
        }
        double mean = sum / n;
        double ss = 0;
        for (Map.Entry<Key, Cell> e : cells.entrySet()) {
            if (e.getKey().actionType() == action && !e.getKey().equals(except)) {
                double d = e.getValue().m - mean;
                ss += d * d;
            }
        }
        double between = n > 1 ? ss / (n - 1) : 0.0;
        return new Pool(mean, Math.max(between, poolFloor) + tau2);
    }

    private void observeSpread(Cell c, double v) {
        c.n = c.n * decay + 1;
        double d = v - c.mean;
        c.mean += d / c.n;
        c.m2 = decay * c.m2 + d * (v - c.mean);
    }

    /** The outcome variance of a cell: its sample variance blended with the prior as worth {@code NU0} observations, floored. */
    private double spreadVariance(Cell c) {
        double s2 = c.n > 1 ? Math.max(0.0, c.m2) / (c.n - 1) : 0.0;
        double blended = c.n > 1 ? (NU0 * s2Prior + (c.n - 1) * s2) / (NU0 + c.n - 1) : s2Prior;
        return Math.max(blended, s2Min);
    }

    private static Key keyOf(WorldObjectType target, ActionType action) {
        return new Key(target == null ? null : target.name(), action);
    }
}
