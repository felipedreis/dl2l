package br.cefetmg.lsi.l2l.creature.bd;

import br.cefetmg.lsi.l2l.creature.arbiter.EpisodicEngram;

import java.util.UUID;

/**
 * Issue #111: one episodic engram, as written when its episode closed. Rows are immutable. {@code k} is the retrieval
 * key ({@code EngramKeyEncoder.KEY_DIM} entries), {@code h} and {@code g} the drive levels at decision time and the
 * discounted return, in {@code FocusTracker.DRIVES} order.
 */
public class EpisodicEngramState implements PersistenceState {

    private final UUID id = UUID.randomUUID();

    private long creatureKey;
    private long seq;
    private long timeMs;
    private long episodeId;
    private String drive;
    private String closeType;
    private long stepCycle;
    private long stepsToOutcome;
    private String action;
    private String objectType;
    private double[] h;
    private double[] g;
    private double bootstrapTerm;
    private double[] k;

    public EpisodicEngramState() { }

    public EpisodicEngramState(long creatureKey, long seq, long timeMs, EpisodicEngram e) {
        this.creatureKey = creatureKey;
        this.seq = seq;
        this.timeMs = timeMs;
        this.episodeId = e.episodeId();
        this.drive = e.drive();
        this.closeType = e.closeType().name();
        this.stepCycle = e.stepCycle();
        this.stepsToOutcome = e.stepsToOutcome();
        this.action = e.action().name();
        this.objectType = e.objectType();
        this.h = e.h();
        this.g = e.g();
        this.bootstrapTerm = e.bootstrapTerm();
        this.k = e.key();
    }

    public UUID getId() { return id; }
    public long getCreatureKey() { return creatureKey; }
    public long getSeq() { return seq; }
    public long getTimeMs() { return timeMs; }
    public long getEpisodeId() { return episodeId; }
    public String getDrive() { return drive; }
    public String getCloseType() { return closeType; }
    public long getStepCycle() { return stepCycle; }
    public long getStepsToOutcome() { return stepsToOutcome; }
    public String getAction() { return action; }
    public String getObjectType() { return objectType; }
    public double getH(int i) { return h[i]; }
    public double getG(int i) { return g[i]; }
    public double getBootstrapTerm() { return bootstrapTerm; }
    public double getK(int i) { return k[i]; }
}
