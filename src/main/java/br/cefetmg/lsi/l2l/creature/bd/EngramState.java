package br.cefetmg.lsi.l2l.creature.bd;

import br.cefetmg.lsi.l2l.creature.common.ActionType;

import javax.persistence.*;

@Entity
@Table(name = "engram_state", schema = "data")
@NamedNativeQueries({
    @NamedNativeQuery(name = "EngramState.getForCreature",
        query = "SELECT es.creature_key, es.action_type, es.lay_cycle, es.reinforced_cycle, " +
                "es.cycle_gap, es.eligibility, es.emotion_delta " +
                "FROM data.engram_state es " +
                "WHERE es.creaturekey = ? ORDER BY es.reinforced_cycle"),
    @NamedNativeQuery(name = "EngramState.countByActionType",
        query = "SELECT es.action_type, count(*) " +
                "FROM data.engram_state es " +
                "WHERE es.creaturekey = ? GROUP BY es.action_type ORDER BY es.action_type")
})
public class EngramState implements PersistenceState {

    @Id @GeneratedValue
    private int id;

    @Column(name = "creature_key")
    private long creatureKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "action_type")
    private ActionType actionType;

    @Column(name = "lay_cycle")
    private long layCycle;

    @Column(name = "reinforced_cycle")
    private long reinforcedCycle;

    @Column(name = "cycle_gap")
    private long cycleGap;

    @Column(name = "eligibility")
    private double eligibility;

    @Column(name = "emotion_delta")
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

    public int getId() { return id; }
    public long getCreatureKey() { return creatureKey; }
    public ActionType getActionType() { return actionType; }
    public long getLayCycle() { return layCycle; }
    public long getReinforcedCycle() { return reinforcedCycle; }
    public long getCycleGap() { return cycleGap; }
    public double getEligibility() { return eligibility; }
    public double getEmotionDelta() { return emotionDelta; }
}
