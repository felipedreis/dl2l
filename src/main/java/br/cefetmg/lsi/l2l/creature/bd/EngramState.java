package br.cefetmg.lsi.l2l.creature.bd;

import br.cefetmg.lsi.l2l.creature.common.ActionType;
import br.cefetmg.lsi.l2l.world.WorldObjectType;

import java.util.UUID;

/**
 * One reinforced memory trace.
 *
 * <p>{@code objectType} and {@code drive} were added on 2026-08-10 because without them the
 * table cannot answer the question it exists to inform. {@code MemoryFilter} values an object as
 * the mean of {@code -emotionDelta x eligibility} over its engrams, aggregated across every
 * drive — so a food that relieves boredom but not hunger still earns positive value. The p84
 * pilot showed exactly that shape: GRAY_APPLE has caloricValue 0 and cannot reduce hunger, yet
 * carried a remembered value of +0.0081, while creatures died with hunger pegged at 6.97/7.
 * Deciding whether that is the cause required splitting an object's value by the drive it
 * actually regulated, which the previous columns made impossible.
 *
 * <p>Campos's LTM value is a deprivation difference, tied to the drive being regulated; ours is
 * drive-agnostic. These two columns are what let that divergence be measured rather than argued.
 */
public class EngramState implements PersistenceState {

    private final UUID id = UUID.randomUUID();

    private long creatureKey;
    private ActionType actionType;
    private long layCycle;
    private long reinforcedCycle;
    private long cycleGap;
    private double eligibility;
    private double emotionDelta;
    /** Object the remembered action targeted; null for self-directed acts (WANDER/SLEEP). */
    private String objectType;
    /** Drive the emotionDelta was measured against — the missing half of what the delta means. */
    private String drive;
    /** That drive's level when the action was chosen, for weighting by how pressing it was. */
    private double driveLevel;

    public EngramState() { }

    public EngramState(long creatureKey, ActionType actionType,
                       long layCycle, long reinforcedCycle, long cycleGap,
                       double eligibility, double emotionDelta,
                       WorldObjectType objectType, String drive, double driveLevel) {
        this.creatureKey = creatureKey;
        this.actionType = actionType;
        this.objectType = objectType != null ? objectType.name() : null;
        this.drive = drive;
        this.driveLevel = driveLevel;
        this.layCycle = layCycle;
        this.reinforcedCycle = reinforcedCycle;
        this.cycleGap = cycleGap;
        this.eligibility = eligibility;
        this.emotionDelta = emotionDelta;
    }

    public UUID getId() { return id; }
    public long getCreatureKey() { return creatureKey; }
    public ActionType getActionType() { return actionType; }
    public long getLayCycle() { return layCycle; }
    public long getReinforcedCycle() { return reinforcedCycle; }
    public long getCycleGap() { return cycleGap; }
    public double getEligibility() { return eligibility; }
    public double getEmotionDelta() { return emotionDelta; }
    public String getObjectType() { return objectType; }
    public String getDrive() { return drive; }
    public double getDriveLevel() { return driveLevel; }
}
