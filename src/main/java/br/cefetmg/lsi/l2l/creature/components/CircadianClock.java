package br.cefetmg.lsi.l2l.creature.components;

/**
 * Circadian oscillator interface. Callers always invoke tick()/driveRate() unconditionally;
 * the implementation decides whether to actually oscillate or stay silent.
 */
public interface CircadianClock {
    void tick();
    double driveRate();

    /** Current oscillator phase in radians; used to modulate neuromodulator baseline synthesis. */
    double phase();
}
