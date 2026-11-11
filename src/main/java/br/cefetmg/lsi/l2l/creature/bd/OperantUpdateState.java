package br.cefetmg.lsi.l2l.creature.bd;

import java.util.UUID;

/**
 * One update of the A2 operant table (docs/plans/operant-per-outcome.md): the sample just recorded into the cell
 * {@code (object type, action)} and the cell's state right after it. Written by {@code Valuation}, one row per
 * outcome on a fruit or plant target. {@code cycle} is the decision cycle of the memory system when it was recorded.
 */
public class OperantUpdateState implements PersistenceState {

    private final UUID id = UUID.randomUUID();

    private long creatureKey;
    private long seq;
    private long timeMs;
    private long cycle;
    private String objectType;
    private String action;
    private double sample;
    private double n;
    private double q;
    private double se;
    private double sigma;

    public OperantUpdateState() { }

    public OperantUpdateState(long creatureKey, long seq, long timeMs, long cycle, String objectType,
                              String action, double sample, double n, double q, double se, double sigma) {
        this.creatureKey = creatureKey;
        this.seq = seq;
        this.timeMs = timeMs;
        this.cycle = cycle;
        this.objectType = objectType;
        this.action = action;
        this.sample = sample;
        this.n = n;
        this.q = q;
        this.se = se;
        this.sigma = sigma;
    }

    public UUID getId() { return id; }
    public long getCreatureKey() { return creatureKey; }
    public long getSeq() { return seq; }
    public long getTimeMs() { return timeMs; }
    public long getCycle() { return cycle; }
    public String getObjectType() { return objectType; }
    public String getAction() { return action; }
    public double getSample() { return sample; }
    public double getN() { return n; }
    public double getQ() { return q; }
    public double getSe() { return se; }
    public double getSigma() { return sigma; }
}
