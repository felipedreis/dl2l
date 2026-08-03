package br.cefetmg.lsi.l2l.creature.components;

/**
 * Circadian oscillator interface. Callers always invoke tick()/driveRate() unconditionally;
 * the implementation decides whether to actually oscillate or stay silent.
 */
public interface CircadianClock {
    /**
     * Issue #79 Phase B: {@code dtSeconds} is the actual wall-clock time elapsed since the
     * last call (not assumed to be exactly one nominal cycle) - implementations weight
     * their advance by it so a late-firing or jittered tick still advances phase by the
     * correct amount of real time, not a fixed per-call increment.
     */
    void tick(double dtSeconds);
    double driveRate();

    /** Current oscillator phase in radians; used to modulate neuromodulator baseline synthesis. */
    double phase();
}
