package br.cefetmg.lsi.l2l.creature.bd;

import br.cefetmg.lsi.l2l.creature.common.ActionType;

import java.util.UUID;

public class EngramState implements PersistenceState {

    private final UUID id = UUID.randomUUID();

    private long creatureKey;
    private ActionType actionType;
    private long layCycle;
    private long reinforcedCycle;
    private long cycleGap;
    private double eligibility;
    private double emotionDelta;

    public EngramState() { }

    public EngramState(long creatureKey, ActionType actionType,
                       long layCycle, long reinforcedCycle, long cycleGap,
                       double eligibility, double emotionDelta) {
        this.creatureKey = creatureKey;
        this.actionType = actionType;
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
}
