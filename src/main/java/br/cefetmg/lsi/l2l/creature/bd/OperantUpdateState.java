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
    /** The cell's estimate just before the update (what it predicted for this sample); NaN / null when not reported or abstaining. */
    private double qPrior = Double.NaN;
    private double sePrior = Double.NaN;
    private String basisPrior;

    public OperantUpdateState() { }

    /** With the cell's estimate just before the update, so calibration needs no reconstruction of the drift. */
    public OperantUpdateState(long creatureKey, long seq, long timeMs, long cycle, String objectType,
                              String action, double sample, double n, double q, double se, double sigma,
                              br.cefetmg.lsi.l2l.creature.arbiter.Estimate prior) {
        this(creatureKey, seq, timeMs, cycle, objectType, action, sample, n, q, se, sigma);
        if (prior != null) {
            this.basisPrior = prior.basis().name();
            this.qPrior = prior.q();
            this.sePrior = Double.isFinite(prior.se()) ? prior.se() : Double.NaN;
        }
    }

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
    public double getQPrior() { return qPrior; }
    public double getSePrior() { return sePrior; }
    public String getBasisPrior() { return basisPrior; }
}
