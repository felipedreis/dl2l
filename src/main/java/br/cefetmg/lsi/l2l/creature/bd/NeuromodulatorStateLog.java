package br.cefetmg.lsi.l2l.creature.bd;

import java.util.UUID;

/**
 * Time series of tonic neuromodulator concentrations (dopamine, serotonin, orexin) and the
 * circadian phase published by {@code NeuromodulatorSystem}. Having the circadian phase here
 * means all four signals share one table, so the validation analysis can overlay them against
 * the base oscillator without joining additional tables. {@code seq} is a monotonic per-component
 * counter.
 */
public class NeuromodulatorStateLog implements PersistenceState {

    private final UUID id = UUID.randomUUID();

    private long creatureKey;
    private long seq;
    private double dopamine;
    private double serotonin;
    private double orexin;
    private double circadianPhase;

    public NeuromodulatorStateLog() { }

    public NeuromodulatorStateLog(long creatureKey, long seq,
                                  double dopamine, double serotonin,
                                  double orexin, double circadianPhase) {
        this.creatureKey    = creatureKey;
        this.seq            = seq;
        this.dopamine       = dopamine;
        this.serotonin      = serotonin;
        this.orexin         = orexin;
        this.circadianPhase = circadianPhase;
    }

    public UUID getId() { return id; }
    public long getCreatureKey() { return creatureKey; }
    public long getSeq() { return seq; }
    public double getDopamine() { return dopamine; }
    public double getSerotonin() { return serotonin; }
    public double getOrexin() { return orexin; }
    public double getCircadianPhase() { return circadianPhase; }
}
