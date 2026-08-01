package br.cefetmg.lsi.l2l.creature.components;

import br.cefetmg.lsi.l2l.common.Constants;

/**
 * Sinusoidal circadian oscillator. Issue #79 Phase B: advances by actual elapsed wall-clock
 * time (weighted against the {@link Constants#TARGET_CYCLE_HZ} nominal rate PHASE_STEP was
 * calibrated against), not by a fixed per-call increment - so it stays correct in wall-clock
 * terms even when {@link #tick} is called late or jittered, robust regardless of machine
 * speed or CPU contention.
 */
public class ActiveCircadianClock implements CircadianClock {

    private double phase = 0.0;
    private static final double PHASE_STEP = 2.0 * Math.PI / Constants.CIRCADIAN_PERIOD_TICKS;

    @Override
    public void tick(double dtSeconds) {
        // "Cycle-equivalent": how many nominal cycles' worth of wall-clock time actually
        // elapsed - 1.0 exactly on-schedule, more if this tick fired late, less if early.
        double cycleEquivalent = dtSeconds * Constants.TARGET_CYCLE_HZ;
        // Modulo, not a single conditional subtract - a sufficiently late tick (we've seen
        // ticks slip by 2-13s under GC-thrashing load) can advance by several multiples of
        // 2π in one call, which one subtraction wouldn't fully unwrap.
        phase = (phase + PHASE_STEP * cycleEquivalent) % (2.0 * Math.PI);
    }

    @Override
    public double driveRate() {
        return Constants.BASE_SLEEP_DRIVE + Constants.CIRCADIAN_AMPLITUDE * Math.sin(phase);
    }

    @Override
    public double phase() {
        return phase;
    }
}
