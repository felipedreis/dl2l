package br.cefetmg.lsi.l2l.creature.arbiter;

import br.cefetmg.lsi.l2l.common.Constants;
import br.cefetmg.lsi.l2l.creature.arbiter.FocusEvent.Closed;
import br.cefetmg.lsi.l2l.creature.arbiter.FocusEvent.Opened;
import br.cefetmg.lsi.l2l.creature.arbiter.FocusEvent.Outcome;
import br.cefetmg.lsi.l2l.creature.arbiter.FocusEvent.Resumed;
import br.cefetmg.lsi.l2l.creature.arbiter.FocusEvent.Suspended;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FocusTrackerTest {

    private static final String H = Constants.HUNGER;
    private static final String S = Constants.SLEEP;
    private static final double BASE = Constants.MIN_AROUSAL_LEVEL;

    private FocusTracker.Params params;
    private FocusTracker tracker;
    private int cycle;

    @BeforeEach
    void setUp() {
        params = FocusTracker.Params.defaults();
        tracker = new FocusTracker(params);
        cycle = 0;
    }

    /** Urgency u maps to level BASE + u since hStar = BASE and scale = 1. */
    private List<FocusEvent> step(double hunger, double sleep) {
        return tracker.update(++cycle, Map.of(
                H, BASE + hunger, S, BASE + sleep,
                Constants.PAIN, BASE, Constants.TEDIUM, BASE));
    }

    @Test
    void staysClosedBelowThetaOn() {
        assertTrue(step(1.9, 0).isEmpty());
        assertTrue(tracker.focalDrive().isEmpty());
    }

    @Test
    void opensAtThetaOn() {
        var events = step(2.0, 0);
        assertEquals(List.of(new Opened(1, H)), events);
        assertEquals(H, tracker.focalDrive().orElseThrow());
    }

    @Test
    void hysteresisBandBetweenOnAndOff() {
        step(2.5, 0);
        assertTrue(step(1.0, 0).isEmpty());
        assertEquals(H, tracker.focalDrive().orElseThrow());
    }

    @Test
    void closesRegulatedAtThetaOff() {
        step(2.5, 0);
        var events = step(0.5, 0);
        assertEquals(List.of(new Closed(2, H, Outcome.REGULATED)), events);
        assertTrue(tracker.focalDrive().isEmpty());
    }

    @Test
    void timesOutAfterTMaxAndDoesNotReopenSameCycle() {
        tracker = new FocusTracker(params.withTMax(3));
        step(2.5, 0);
        step(2.5, 0);
        step(2.5, 0);
        step(2.5, 0);
        var events = step(2.5, 0);
        assertEquals(List.of(new Closed(5, H, Outcome.TIMED_OUT)), events);
        assertEquals(List.of(new Opened(6, H)), step(2.5, 0));
    }

    @Test
    void closesOnDeath() {
        step(2.5, 0);
        assertEquals(List.of(new Closed(2, H, Outcome.DEATH)), tracker.onDeath(2));
        assertTrue(tracker.focalDrive().isEmpty());
    }

    @Test
    void noSwitchWithinDelta() {
        step(2.5, 0);
        assertTrue(step(2.5, 2.8).isEmpty());
        assertEquals(H, tracker.focalDrive().orElseThrow());
    }

    @Test
    void preemptedOnlyAboveDelta() {
        step(2.5, 0);
        var events = step(2.5, 2.9);
        assertEquals(List.of(new Suspended(2, H, S), new Opened(2, S)), events);
        assertEquals(S, tracker.focalDrive().orElseThrow());
        assertTrue(tracker.isSuspended(H));
    }

    @Test
    void emergencyOverrideIgnoresDelta() {
        step(3.9, 0);
        var events = step(3.9, 4.05);
        assertEquals(List.of(new Suspended(2, H, S), new Opened(2, S)), events);
    }

    @Test
    void noOverrideWhenFocalAboveCrit() {
        step(4.05, 0);
        assertTrue(step(4.05, 4.3).isEmpty());
        assertEquals(H, tracker.focalDrive().orElseThrow());
    }

    @Test
    void suspendedResumesWithinRKeepingActiveCycles() {
        tracker = new FocusTracker(params.withTMax(4));
        step(2.5, 0);            // open H (0 active)
        step(2.5, 3.0);          // H active=1, pre-empted by S
        step(1.0, 3.0);          // S holds
        var events = step(3.0, 1.0);   // S falls, H 3.0 > S 1.0 + delta
        assertEquals(List.of(new Suspended(4, S, H), new Resumed(4, H)), events);
        step(3.0, 1.0);          // H active=2
        step(3.0, 1.0);          // 3
        step(3.0, 1.0);          // 4
        var out = step(3.0, 1.0);        // 5 > tMax: elapsed cycles were kept
        assertEquals(List.of(new Closed(8, H, Outcome.TIMED_OUT)), out);
    }

    @Test
    void suspendedExpiresTimedOutWhenStillUrgent() {
        tracker = new FocusTracker(params.withResumeWindow(2));
        step(2.5, 0);
        step(2.5, 3.0);          // H suspended at 2, deadline 4
        step(2.5, 3.0);
        step(2.5, 3.0);
        var events = step(2.5, 3.0);     // cycle 5
        assertEquals(List.of(new Closed(5, H, Outcome.TIMED_OUT)), events);
    }

    @Test
    void suspendedClosesRegulatedWhenDriveFalls() {
        step(2.5, 0);
        step(2.5, 3.0);
        var events = step(0.4, 3.0);
        assertEquals(List.of(new Closed(3, H, Outcome.REGULATED)), events);
    }

    @Test
    void deterministicTieBreakByDriveOrder() {
        var events = step(3.0, 3.0);
        assertEquals(List.of(new Opened(1, H)), events);
    }

    @Test
    void corridorNoSwitchingWhenUrgenciesStayWithinDelta() {
        // Two drives drifting together, never more than delta (0.3) apart: no focus trade.
        step(2.2, 2.0);
        for (int i = 0; i < 200; i++) {
            double h = 2.5 + 1.0 * Math.sin(i / 7.0);
            double s = h + 0.29 * Math.sin(i / 3.0);
            var events = step(h, s);
            assertTrue(events.isEmpty(), "unexpected event at cycle " + cycle + ": " + events);
        }
        assertEquals(H, tracker.focalDrive().orElseThrow());
    }
}
