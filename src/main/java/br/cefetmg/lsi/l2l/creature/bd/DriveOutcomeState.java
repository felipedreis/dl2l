package br.cefetmg.lsi.l2l.creature.bd;

import br.cefetmg.lsi.l2l.common.SequentialId;

import java.util.UUID;

/**
 * Issue #100: the per-drive outcome vector K = (Δhunger, Δsleep, Δpain, Δtedium) of one regulation
 * event, attributed to the action that caused it. K is the <em>realized</em> (post-clamp) change in
 * drive level and is recorded independently of need — need is applied when K is read, as
 * V(K | H) = d(H) − d(H + K) (arbitration draft §4.1). Rows are additive: summing them over any
 * window gives that window's change in each drive due to regulation.
 *
 * <p>{@code action} is the originating {@code ActionType} name, or {@link #BASELINE} when the
 * change has no deliberate action behind it (metabolic drift, circadian pressure, immune decay,
 * passive collision pain).
 */
public class DriveOutcomeState implements PersistenceState {

    /** Pseudo-action for changes not caused by a deliberate act. Not an {@code ActionType} member. */
    public static final String BASELINE = "BASELINE";

    private final UUID id = UUID.randomUUID();

    private long creatureKey;
    private long seq;
    private long timeMs;
    private String action;
    private String objectType;
    private SequentialId target;
    private double dHunger;
    private double dSleep;
    private double dPain;
    private double dTedium;
    private ChangeStimulusState changeStimulusState;

    public DriveOutcomeState() { }

    public DriveOutcomeState(long creatureKey, long seq, long timeMs, String action, String objectType,
                             SequentialId target, double dHunger, double dSleep, double dPain,
                             double dTedium, ChangeStimulusState changeStimulusState) {
        this.creatureKey = creatureKey;
        this.seq = seq;
        this.timeMs = timeMs;
        this.action = action;
        this.objectType = objectType;
        this.target = target;
        this.dHunger = dHunger;
        this.dSleep = dSleep;
        this.dPain = dPain;
        this.dTedium = dTedium;
        this.changeStimulusState = changeStimulusState;
    }

    public UUID getId() { return id; }
    public long getCreatureKey() { return creatureKey; }
    public long getSeq() { return seq; }
    public long getTimeMs() { return timeMs; }
    public String getAction() { return action; }
    public String getObjectType() { return objectType; }
    public SequentialId getTarget() { return target; }
    public double getDHunger() { return dHunger; }
    public double getDSleep() { return dSleep; }
    public double getDPain() { return dPain; }
    public double getDTedium() { return dTedium; }
    public ChangeStimulusState getChangeStimulusState() { return changeStimulusState; }
}
