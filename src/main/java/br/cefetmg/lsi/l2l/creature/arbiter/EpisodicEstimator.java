package br.cefetmg.lsi.l2l.creature.arbiter;

import br.cefetmg.lsi.l2l.creature.common.Action;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Episodic controller of the uncertainty arbitration paper (sections 4.3-4.4, issue #113): an MFEC-style kNN over the
 * engrams of one action.
 *
 * <p>For each candidate, the query key is the candidate's percept with the creature's current drives
 * ({@link EngramKeyEncoder}); the {@code m} nearest stored keys of the same action (Euclidean, precision weights 1)
 * are the neighbours. With {@code D_j} the distance to neighbour {@code j}, {@code b} the median of the {@code D_j} and
 * {@code v_j = V(G_j | H_now) + bootstrapTerm_j} the neighbour's value read against the drives <em>now</em>:
 * <ul>
 *   <li>{@code omega_j = exp(-D_j^2 / 2 b^2)} (equal weights when {@code b = 0});</li>
 *   <li>{@code Q = sum(omega v) / sum(omega)};</li>
 *   <li>{@code n_eff = (sum omega)^2 / sum(omega^2)};</li>
 *   <li>{@code SE = sqrt(sigma^2 / n_eff + beta^2)}, {@code beta = L * Dbar}, {@code Dbar} the omega-weighted mean
 *       distance and {@code L = 0.5}.</li>
 * </ul>
 * {@code sigma} is the omega-weighted spread of the neighbours' values, with the reliability correction
 * {@code n_eff / (n_eff - 1)}, blended with the prior as in {@link OperantEstimator}:
 * {@code sigma^2 = (NU0 sigma0^2 + (n_eff - 1) s^2) / (NU0 + n_eff - 1)} with {@code NU0 = 1} and {@code sigma0 =}
 * {@link #PRIOR_SIGMA}. A deliberate departure from the paper's {@code sigma = s}, for the same reason as there: one
 * neighbour, or neighbours that agree exactly, would give {@code sigma = 0} and an error bar of 0 from a single memory.
 * The blend is {@code sigma0} for one neighbour and tends to {@code s} as evidence accumulates.
 *
 * <p>{@code sigma0} is on the scale of the values, not of the operant table's valence: {@code V(G | H)} is a deficit
 * reduction, about 0.01-0.25 per step in the pilot (EAT on food 0.11-0.24, APPROACH toward food 0.02-0.05, toward gray 0.006), so
 * {@link #PRIOR_SIGMA} = 0.1 is about one meal's worth. A first version copied the operant's {@code sigma0 = 1}: replayed on
 * the pilot's real episodes (analysis/diagnostics/a3_episodic_replay.py) its SE was about 0.11 against value gaps of 0.005-0.01, so the
 * controller was decisive in 0 of 3000 queries; with 0.1 it is in 15-19% and prefers APPROACH toward food (59%) over gray (37%).
 *
 * <p>Sparse (one neighbour: {@code sigma^2 / n_eff = 1}), distant ({@code beta} grows with {@code Dbar}) or disagreeing
 * ({@code s} grows) memories give a wide {@code SE}. Note that with {@code b} the median distance, {@code n_eff} itself
 * stays high when the neighbours are all far (the weights adapt to the spread); distance is what {@code beta} is for.
 *
 * <p>An action with no engram gives {@code Estimate(0, sigma0, sigma0, nEff = 0)}: no value, the prior's error bar.
 *
 * <p>{@link #estimate} is read-only. Not thread-safe: it lives behind a TypedActor facade in {@code CreatureActor}.
 */
public final class EpisodicEstimator implements EpisodicStore {

    /** Neighbours per candidate (paper section 4.3). */
    public static final int DEFAULT_NEIGHBOURS = 10;
    /** Weight of the distance bias {@code beta = L * Dbar} (paper section 4.4). */
    public static final double DEFAULT_BIAS = 0.5;

    /** Prior spread of a value, about one meal's worth of drive deficit (see the class javadoc). */
    public static final double PRIOR_SIGMA = 0.1;
    private static final double SIGMA0 = PRIOR_SIGMA;
    private static final double NU0 = 1.0;

    private final EpisodicEngramStore store;
    private final EngramKeyEncoder encoder;
    private final HomeostaticValue value;
    private final int neighbours;
    private final double bias;

    /** Defaults: a fresh store of {@link EpisodicEngramStore#DEFAULT_CAP_PER_ACTION}, m = 10, L = 0.5. */
    public EpisodicEstimator(EngramKeyEncoder encoder, HomeostaticValue value) {
        this(new EpisodicEngramStore(), encoder, value, DEFAULT_NEIGHBOURS, DEFAULT_BIAS);
    }

    /**
     * @param store      where the engrams live
     * @param encoder    builds the query key
     * @param value      {@code V(G | H)}
     * @param neighbours {@code m}, at least 1
     * @param bias       {@code L}, finite and non-negative
     * @throws IllegalArgumentException on a null argument, {@code neighbours < 1}, or a bad {@code bias}
     */
    public EpisodicEstimator(EpisodicEngramStore store, EngramKeyEncoder encoder, HomeostaticValue value,
                             int neighbours, double bias) {
        if (store == null || encoder == null || value == null) {
            throw new IllegalArgumentException("store, encoder and value must not be null");
        }
        if (neighbours < 1) {
            throw new IllegalArgumentException("neighbours must be at least 1: " + neighbours);
        }
        if (!Double.isFinite(bias) || bias < 0) {
            throw new IllegalArgumentException("bias must be finite and non-negative: " + bias);
        }
        this.store = store;
        this.encoder = encoder;
        this.value = value;
        this.neighbours = neighbours;
        this.bias = bias;
    }

    @Override
    public void add(List<EpisodicEngram> engrams) {
        for (EpisodicEngram e : engrams) {
            store.add(e);
        }
    }

    @Override
    public int size() {
        return store.size();
    }

    /**
     * @param candidates candidate actions; may be empty
     * @param h          the drive levels now, in {@link FocusTracker#DRIVES} order; not mutated
     * @param focalDrive unused: values are read against all drives
     * @return one estimate per candidate, in order; unmodifiable
     * @throws IllegalArgumentException if {@code candidates} is null or holds an action without a perception, or
     *                                  {@code h} is wrong
     */
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
            out.add(estimateOne(action, h));
        }
        return Collections.unmodifiableList(out);
    }

    private Estimate estimateOne(Action action, double[] h) {
        double[] query = encoder.encode(action.perception, h);

        // The m nearest of this action's engrams, by insertion into small parallel arrays.
        double[] dist = new double[neighbours];
        EpisodicEngram[] near = new EpisodicEngram[neighbours];
        int n = 0;
        for (EpisodicEngram e : store.of(action.type)) {
            double d = distance(query, e.keyUnsafe());
            if (n == neighbours && d >= dist[n - 1]) {
                continue;
            }
            int at = n < neighbours ? n++ : n - 1;
            while (at > 0 && dist[at - 1] > d) {
                dist[at] = dist[at - 1];
                near[at] = near[at - 1];
                at--;
            }
            dist[at] = d;
            near[at] = e;
        }
        if (n == 0) {
            return new Estimate(0, SIGMA0, SIGMA0, 0);
        }

        double b = n % 2 == 1 ? dist[n / 2] : 0.5 * (dist[n / 2 - 1] + dist[n / 2]);
        double[] omega = new double[n];
        double[] v = new double[n];
        double sumW = 0, sumW2 = 0, sumWv = 0, sumWd = 0;
        for (int j = 0; j < n; j++) {
            omega[j] = b > 1e-12 ? Math.exp(-dist[j] * dist[j] / (2 * b * b)) : 1.0;
            v[j] = value.value(near[j].gUnsafe(), h) + near[j].bootstrapTerm();
            sumW += omega[j];
            sumW2 += omega[j] * omega[j];
            sumWv += omega[j] * v[j];
            sumWd += omega[j] * dist[j];
        }
        double q = sumWv / sumW;
        double nEff = sumW * sumW / sumW2;
        double meanDistance = sumWd / sumW;
        double weightedVariance = 0;
        for (int j = 0; j < n; j++) {
            weightedVariance += omega[j] * (v[j] - q) * (v[j] - q);
        }
        weightedVariance /= sumW;
        double sampleVariance = nEff > 1 ? weightedVariance * nEff / (nEff - 1) : 0;
        double sigmaSquared = (NU0 * SIGMA0 * SIGMA0 + (nEff - 1) * sampleVariance) / (NU0 + nEff - 1);
        double sigma = Math.sqrt(sigmaSquared);
        double beta = bias * meanDistance;
        double se = Math.sqrt(sigmaSquared / nEff + beta * beta);
        return new Estimate(q, se, sigma, nEff);
    }

    private static double distance(double[] a, double[] b) {
        double s = 0;
        for (int i = 0; i < a.length; i++) {
            double d = a[i] - b[i];
            s += d * d;
        }
        return Math.sqrt(s);
    }
}
