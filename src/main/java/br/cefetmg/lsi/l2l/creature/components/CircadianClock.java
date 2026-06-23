package br.cefetmg.lsi.l2l.creature.components;

import br.cefetmg.lsi.l2l.common.Constants;

/**
 * Sinusoidal circadian oscillator paced by the creature's cognitive cycle count.
 * Decays by cognitive-cycle count (not wall-clock) so the rhythm is immune to
 * machine-load effects and consistent with how all other creature rhythms are defined.
 */
public class CircadianClock {

    private double phase = 0.0;
    private static final double PHASE_STEP = 2.0 * Math.PI / Constants.CIRCADIAN_PERIOD_TICKS;

    public void tick() {
        phase += PHASE_STEP;
        if (phase >= 2.0 * Math.PI) {
            phase -= 2.0 * Math.PI;
        }
    }

    public double driveRate() {
        return Constants.BASE_SLEEP_DRIVE + Constants.CIRCADIAN_AMPLITUDE * Math.sin(phase);
    }
}
