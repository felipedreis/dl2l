package br.cefetmg.lsi.l2l.creature.bd;

import java.util.UUID;

/**
 * Time series of cortisol tonic level and resulting stress affect published by
 * {@code EndocrineSystem}. Used in the validation analysis to overlay cortisol against
 * the circadian phase (held in {@code NeuromodulatorStateLog}) and confirm the daily
 * morning pulse and stressor response are working as designed.
 */
public class EndocrineStateLog implements PersistenceState {

    private final UUID id = UUID.randomUUID();

    private long creatureKey;
    private long seq;
    private double cortisolTonic;
    private double stressLevel;

    public EndocrineStateLog() { }

    public EndocrineStateLog(long creatureKey, long seq, double cortisolTonic, double stressLevel) {
        this.creatureKey  = creatureKey;
        this.seq          = seq;
        this.cortisolTonic = cortisolTonic;
        this.stressLevel  = stressLevel;
    }

    public UUID getId() { return id; }
    public long getCreatureKey() { return creatureKey; }
    public long getSeq() { return seq; }
    public double getCortisolTonic() { return cortisolTonic; }
    public double getStressLevel() { return stressLevel; }
}
