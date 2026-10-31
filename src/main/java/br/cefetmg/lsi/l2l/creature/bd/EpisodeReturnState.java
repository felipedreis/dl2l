package br.cefetmg.lsi.l2l.creature.bd;

import java.util.UUID;

/**
 * Issue #110: one closed decision of an episode, with its discounted return (uncertainty arbitration
 * paper, section 4.2). Written by {@code Valuation} from the {@code EpisodeBuffer}'s output, one row per
 * step. Rows are immutable once written.
 *
 * <p>{@code g} is the return {@code G_t} per drive, {@code h} the drive levels at decision time, both in
 * {@code FocusTracker.DRIVES} order. {@code bootstrap} is {@code NaN} unless the close was a pre-emption,
 * and is then stored as a null column. {@code value} is {@code V(G_t | H_t)} including the bootstrap term.
 */
public class EpisodeReturnState implements PersistenceState {

    private final UUID id = UUID.randomUUID();

    private long creatureKey;
    private long seq;
    private long timeMs;
    private long episodeId;
    private String drive;
    private String closeType;
    private long stepCycle;
    private long stepsToClose;
    private String action;
    private String objectType;
    private double distance;
    private double[] h;
    private double[] g;
    private double bootstrap;
    private double value;

    public EpisodeReturnState() { }

    public EpisodeReturnState(long creatureKey, long seq, long timeMs, long episodeId, String drive,
                              String closeType, long stepCycle, long stepsToClose, String action,
                              String objectType, double distance, double[] h, double[] g,
                              double bootstrap, double value) {
        this.creatureKey = creatureKey;
        this.seq = seq;
        this.timeMs = timeMs;
        this.episodeId = episodeId;
        this.drive = drive;
        this.closeType = closeType;
        this.stepCycle = stepCycle;
        this.stepsToClose = stepsToClose;
        this.action = action;
        this.objectType = objectType;
        this.distance = distance;
        this.h = h.clone();
        this.g = g.clone();
        this.bootstrap = bootstrap;
        this.value = value;
    }

    public UUID getId() { return id; }
    public long getCreatureKey() { return creatureKey; }
    public long getSeq() { return seq; }
    public long getTimeMs() { return timeMs; }
    public long getEpisodeId() { return episodeId; }
    public String getDrive() { return drive; }
    public String getCloseType() { return closeType; }
    public long getStepCycle() { return stepCycle; }
    public long getStepsToClose() { return stepsToClose; }
    public String getAction() { return action; }
    public String getObjectType() { return objectType; }
    public double getDistance() { return distance; }
    public double getH(int i) { return h[i]; }
    public double getG(int i) { return g[i]; }
    public double getBootstrap() { return bootstrap; }
    public double getValue() { return value; }
}
