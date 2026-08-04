package br.cefetmg.lsi.l2l.creature.bd;

import br.cefetmg.lsi.l2l.creature.common.ActionType;

import java.util.UUID;

/**
 * One row of the operant conditioning table at the moment it changed: the probability the
 * creature now assigns to {@code action} when facing {@code target}, right after
 * {@code reinforcedAction} was evaluated and its probability moved by {@code delta}.
 *
 * <p>Valuation writes one of these per action in the affected target's table (six rows per
 * valuation event), from both the legacy and the expectancy path — the legacy-minimal
 * experiment arms have expectancy disabled and would otherwise produce no conditioning data
 * at all. Targets that were not evaluated are unchanged and simply not written; readers
 * forward-fill.
 *
 * <p>{@code probability} is the <em>raw</em> stored value, not a normalised share. The two are
 * not the same: {@code ActionProbability.varyProbability} clamps at 0 while
 * {@code OperantConditioningActor} applies the compensating {@code -delta/(n-1)} to the other
 * entries unconditionally, so the table's sum drifts away from its initial 100 once any entry
 * bottoms out. {@code ActionProbabilityFilter} hides this by normalising at selection time, and
 * analysis must do the same — plot {@code p_i / sum(p)}, never the raw column.
 */
public class ActionProbabilityState implements PersistenceState {

    private final UUID id = UUID.randomUUID();

    private long creatureKey;
    private long seq;
    private long timeMs;
    private long cycle;
    private String target;
    private ActionType action;
    private double probability;
    private ActionType reinforcedAction;
    private double delta;

    public ActionProbabilityState() { }

    public ActionProbabilityState(long creatureKey, long seq, long timeMs, long cycle,
                                  String target, ActionType action, double probability,
                                  ActionType reinforcedAction, double delta) {
        this.creatureKey = creatureKey;
        this.seq = seq;
        this.timeMs = timeMs;
        this.cycle = cycle;
        this.target = target;
        this.action = action;
        this.probability = probability;
        this.reinforcedAction = reinforcedAction;
        this.delta = delta;
    }

    public UUID getId()                    { return id; }
    public long getCreatureKey()           { return creatureKey; }
    public long getSeq()                   { return seq; }
    public long getTimeMs()                { return timeMs; }
    public long getCycle()                 { return cycle; }
    public String getTarget()              { return target; }
    public ActionType getAction()          { return action; }
    public double getProbability()         { return probability; }
    public ActionType getReinforcedAction() { return reinforcedAction; }
    public double getDelta()               { return delta; }
}
