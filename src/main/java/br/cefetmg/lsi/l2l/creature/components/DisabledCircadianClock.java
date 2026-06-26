package br.cefetmg.lsi.l2l.creature.components;

/**
 * Null-object implementation of CircadianClock. tick() is a no-op and driveRate()
 * returns 0.0, so no adenosinergic stimulus is ever emitted when the circadian
 * cycle is disabled — without any conditional logic in the caller.
 */
public class DisabledCircadianClock implements CircadianClock {

    @Override
    public void tick() {}

    @Override
    public double driveRate() {
        return 0.0;
    }
}
