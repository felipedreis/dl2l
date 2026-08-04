package br.cefetmg.lsi.l2l.creature.bd;

import java.util.function.IntSupplier;

/**
 * Pure hysteresis/trip logic for the fast-fail BDActor backlog watchdog (see
 * docs/plans/arrow-ipc-write-path.md, W3) - kept separate from the daemon {@link Thread} that
 * drives it (see {@code PersistenceExtension.Impl}) specifically so the trip decision is
 * unit-testable without actually sleeping 5s between samples or calling {@code Runtime.halt}.
 *
 * <p>No feedback loop into the simulation (no adaptive tick degradation, no blocking queue) -
 * per the plan's explicit user decision, an out-of-control backlog aborts loudly in seconds via
 * {@code onTrip} instead of the 13-minute GC livelock a soft degradation strategy risks masking.
 * {@link #DEFAULT_QUEUE_LIMIT} sits comfortably above the ~8k envelope depth healthy runs peak
 * at and comfortably below the ≥1.2M depths every real crash measured - see
 * docs/plans/issue-77-bdactor-oom-fix.md. Two consecutive over-threshold samples (not one) avoid
 * tripping on a single transient spike.
 */
final class PersistenceWatchdog {

    static final String QUEUE_LIMIT_ENV = "DL2L_BDACTOR_QUEUE_LIMIT";
    static final long DEFAULT_QUEUE_LIMIT = 500_000;
    static final int CONSECUTIVE_SAMPLES_TO_TRIP = 2;

    private final IntSupplier queueDepth;
    private final long threshold;
    private final Runnable onTrip;

    private int consecutiveOverThreshold = 0;
    private volatile boolean tripped = false;

    PersistenceWatchdog(IntSupplier queueDepth, long threshold, Runnable onTrip) {
        this.queueDepth = queueDepth;
        this.threshold = threshold;
        this.onTrip = onTrip;
    }

    /**
     * Called once per sample tick. Fires {@code onTrip} exactly once, on the sample that
     * completes {@link #CONSECUTIVE_SAMPLES_TO_TRIP} consecutive over-threshold reads; any
     * below-threshold sample in between resets the streak.
     */
    synchronized void sample() {
        if (tripped) return;
        long depth = queueDepth.getAsInt();
        if (depth >= threshold) {
            consecutiveOverThreshold++;
            if (consecutiveOverThreshold >= CONSECUTIVE_SAMPLES_TO_TRIP) {
                tripped = true;
                onTrip.run();
            }
        } else {
            consecutiveOverThreshold = 0;
        }
    }

    boolean tripped() {
        return tripped;
    }

    static long thresholdFromEnv() {
        String v = System.getenv(QUEUE_LIMIT_ENV);
        return v != null ? Long.parseLong(v) : DEFAULT_QUEUE_LIMIT;
    }
}
