package br.cefetmg.lsi.l2l.creature.bd;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the fast-fail queue-depth watchdog's hysteresis/trip decision
 * (docs/plans/arrow-ipc-write-path.md, W3) - deliberately decoupled from the daemon
 * {@link Thread} and {@code Runtime.halt} that drive it in production (see
 * {@code PersistenceExtension.Impl}), so this exercises the decision logic directly and fast,
 * with no sleeping and no process exit.
 */
class PersistenceWatchdogTest {

    @Test
    void doesNotTripOnASingleOverThresholdSample() {
        AtomicInteger trips = new AtomicInteger();
        PersistenceWatchdog watchdog = new PersistenceWatchdog(() -> 1_000_000, 500_000, trips::incrementAndGet);

        watchdog.sample();

        assertEquals(0, trips.get(), "a lone spike must not trip - two consecutive samples are required");
        assertFalse(watchdog.tripped());
    }

    @Test
    void tripsOnTheSecondConsecutiveOverThresholdSample() {
        AtomicInteger trips = new AtomicInteger();
        PersistenceWatchdog watchdog = new PersistenceWatchdog(() -> 1_000_000, 500_000, trips::incrementAndGet);

        watchdog.sample();
        watchdog.sample();

        assertEquals(1, trips.get());
        assertTrue(watchdog.tripped());
    }

    @Test
    void tripsExactlyOnceEvenIfSampledAgainAfterTripping() {
        AtomicInteger trips = new AtomicInteger();
        PersistenceWatchdog watchdog = new PersistenceWatchdog(() -> 1_000_000, 500_000, trips::incrementAndGet);

        watchdog.sample();
        watchdog.sample();
        watchdog.sample();
        watchdog.sample();

        assertEquals(1, trips.get(), "onTrip must fire exactly once, not once per sample after tripping");
    }

    @Test
    void aBelowThresholdSampleResetsTheStreak() {
        AtomicInteger trips = new AtomicInteger();
        AtomicLong depth = new AtomicLong(1_000_000);
        PersistenceWatchdog watchdog = new PersistenceWatchdog(() -> (int) depth.get(), 500_000, trips::incrementAndGet);

        watchdog.sample(); // 1st over-threshold
        depth.set(100); // healthy blip recovers
        watchdog.sample(); // resets the streak
        depth.set(1_000_000);
        watchdog.sample(); // 1st over-threshold again (streak was reset)

        assertEquals(0, trips.get(), "streak must restart, not carry over across a healthy sample");
        assertFalse(watchdog.tripped());

        watchdog.sample(); // 2nd consecutive over-threshold - now it trips
        assertEquals(1, trips.get());
    }

    @Test
    void depthExactlyAtThresholdCounts() {
        AtomicInteger trips = new AtomicInteger();
        PersistenceWatchdog watchdog = new PersistenceWatchdog(() -> 500_000, 500_000, trips::incrementAndGet);

        watchdog.sample();
        watchdog.sample();

        assertEquals(1, trips.get(), ">= threshold, not > threshold, must trip");
    }

    @Test
    void healthyDepthNeverTrips() {
        AtomicInteger trips = new AtomicInteger();
        PersistenceWatchdog watchdog = new PersistenceWatchdog(() -> 8_000, 500_000, trips::incrementAndGet);

        for (int i = 0; i < 100; i++) watchdog.sample();

        assertEquals(0, trips.get());
        assertFalse(watchdog.tripped());
    }

    @Test
    void thresholdFromEnvDefaultsTo500000WhenUnset() {
        // DL2L_BDACTOR_QUEUE_LIMIT is not set in this test environment.
        assertEquals(500_000L, PersistenceWatchdog.thresholdFromEnv());
    }
}
