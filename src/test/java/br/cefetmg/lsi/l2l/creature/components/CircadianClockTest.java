package br.cefetmg.lsi.l2l.creature.components;

import br.cefetmg.lsi.l2l.common.Constants;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CircadianClockTest {

    // --- ActiveCircadianClock ---

    @Test
    void activeClock_initialDriveEqualsBase() {
        ActiveCircadianClock clock = new ActiveCircadianClock();
        // sin(0) == 0, so initial rate == BASE_SLEEP_DRIVE
        assertEquals(Constants.BASE_SLEEP_DRIVE, clock.driveRate(), 1e-12);
    }

    @Test
    void activeClock_driveRateIsAlwaysPositive() {
        ActiveCircadianClock clock = new ActiveCircadianClock();
        for (int i = 0; i < Constants.CIRCADIAN_PERIOD_TICKS * 3; i++) {
            assertTrue(clock.driveRate() > 0,
                    "driveRate should always be positive at tick " + i);
            clock.tick();
        }
    }

    @Test
    void activeClock_driveRateOscillates() {
        ActiveCircadianClock clock = new ActiveCircadianClock();
        double min = Double.MAX_VALUE, max = Double.MIN_VALUE;
        for (int i = 0; i < Constants.CIRCADIAN_PERIOD_TICKS; i++) {
            double rate = clock.driveRate();
            if (rate < min) min = rate;
            if (rate > max) max = rate;
            clock.tick();
        }
        // After a full period the oscillator must have both a clear minimum and maximum
        double expected_min = Constants.BASE_SLEEP_DRIVE - Constants.CIRCADIAN_AMPLITUDE;
        double expected_max = Constants.BASE_SLEEP_DRIVE + Constants.CIRCADIAN_AMPLITUDE;
        assertEquals(expected_min, min, 1e-6);
        assertEquals(expected_max, max, 1e-6);
    }

    @Test
    void activeClock_phaseWrapsAfterOnePeriod() {
        ActiveCircadianClock clock = new ActiveCircadianClock();
        double rateAtStart = clock.driveRate();
        for (int i = 0; i < Constants.CIRCADIAN_PERIOD_TICKS; i++) clock.tick();
        assertEquals(rateAtStart, clock.driveRate(), 1e-10,
                "driveRate should return to start value after one full period");
    }

    // --- DisabledCircadianClock ---

    @Test
    void disabledClock_driveRateIsAlwaysZero() {
        DisabledCircadianClock clock = new DisabledCircadianClock();
        assertEquals(0.0, clock.driveRate());
        for (int i = 0; i < 100; i++) {
            clock.tick();
            assertEquals(0.0, clock.driveRate(),
                    "DisabledCircadianClock must always return 0.0");
        }
    }

    @Test
    void disabledClock_tickIsNoOp() {
        DisabledCircadianClock clock = new DisabledCircadianClock();
        // tick() must not throw and must leave driveRate() at 0
        assertDoesNotThrow(() -> { for (int i = 0; i < 1000; i++) clock.tick(); });
        assertEquals(0.0, clock.driveRate());
    }

    // --- Polymorphism via interface ---

    @Test
    void circadianInterface_activeImplementationIsNonZero() {
        CircadianClock clock = new ActiveCircadianClock();
        clock.tick();
        assertTrue(clock.driveRate() > 0);
    }

    @Test
    void circadianInterface_disabledImplementationIsZero() {
        CircadianClock clock = new DisabledCircadianClock();
        clock.tick();
        assertEquals(0.0, clock.driveRate());
    }
}
