package br.cefetmg.lsi.l2l.creature.components;

import br.cefetmg.lsi.l2l.common.Constants;

/**
 * Sinusoidal circadian oscillator paced by the creature's cognitive cycle count.
 * Rhythm is immune to machine-load effects because it advances by cognitive cycle,
 * not wall-clock time.
 */
public class ActiveCircadianClock implements CircadianClock {

    private double phase = 0.0;
    private static final double PHASE_STEP = 2.0 * Math.PI / Constants.CIRCADIAN_PERIOD_TICKS;

    @Override
    public void tick() {
        phase += PHASE_STEP;
        if (phase >= 2.0 * Math.PI) {
            phase -= 2.0 * Math.PI;
        }
    }

    @Override
    public double driveRate() {
        return Constants.BASE_SLEEP_DRIVE + Constants.CIRCADIAN_AMPLITUDE * Math.sin(phase);
    }
}
