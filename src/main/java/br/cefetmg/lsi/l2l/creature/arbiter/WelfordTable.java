package br.cefetmg.lsi.l2l.creature.arbiter;

import java.util.HashMap;
import java.util.Map;

/**
 * Keyed running mean and spread with exponential forgetting (Welford's method).
 *
 * <p>Policy-free: no binning, priors or {@link Estimate}s here. On each {@link #observe} only the
 * key being updated is decayed. With {@code d = decay}, an update of key {@code k} with value
 * {@code v} is:
 * <pre>
 *   n  &larr; d * n
 *   n' = n + 1;  delta = v - mean;  mean &larr; mean + delta / n'
 *   M2 &larr; d * M2 + delta * (v - mean);  n &larr; n'
 * </pre>
 * With {@code decay = 1} this is exactly Welford's recurrence. Not thread-safe.
 *
 * @param <K> key type
 */
final class WelfordTable<K> {

    /**
     * Statistics of one key.
     *
     * @param n    decayed count; {@code 0} for a key never observed
     * @param mean decayed mean; {@code 0} for a key never observed
     * @param sd   sample standard deviation {@code sqrt(M2 / (n - 1))}; {@code NaN} when {@code n <= 1}
     */
    record Stats(double n, double mean, double sd) {
    }

    private static final class Cell {
        double n;
        double mean;
        double m2;
    }

    private final double decay;
    private final Map<K, Cell> cells = new HashMap<>();

    /**
     * @param decay factor applied to the key's count and M2 on each of its updates; in (0, 1]
     * @throws IllegalArgumentException if {@code decay} is not in (0, 1]
     */
    WelfordTable(double decay) {
        if (!(decay > 0 && decay <= 1)) {
            throw new IllegalArgumentException("decay must be in (0, 1]: " + decay);
        }
        this.decay = decay;
    }

    /**
     * @param key   key to update; created on first use
     * @param value finite observation (validated by the caller)
     */
    void observe(K key, double value) {
        Cell c = cells.computeIfAbsent(key, k -> new Cell());
        c.n *= decay;
        double nNew = c.n + 1;
        double delta = value - c.mean;
        c.mean += delta / nNew;
        c.m2 = decay * c.m2 + delta * (value - c.mean);
        c.n = nNew;
    }

    /**
     * Read-only: an unseen key returns {@code Stats(0, 0, NaN)} and is not inserted.
     *
     * @param key key to read
     * @return current statistics of {@code key}
     */
    Stats stats(K key) {
        Cell c = cells.get(key);
        if (c == null) {
            return new Stats(0, 0, Double.NaN);
        }
        double sd = c.n > 1 ? Math.sqrt(Math.max(0, c.m2) / (c.n - 1)) : Double.NaN;
        return new Stats(c.n, c.mean, sd);
    }
}
