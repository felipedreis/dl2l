package br.cefetmg.lsi.l2l.creature.bd;

import br.cefetmg.lsi.l2l.creature.common.ActionType;

import java.util.UUID;

/**
 * One reward-prediction event from Valuation (the VTA/SNc role): the expected reward, the observed
 * reward, and the resulting prediction error, tagged with the dominant drive, its level, and the
 * expectancy variant in use. This is the primary data for the issue-57 experiment — prediction
 * accuracy (expected vs reward) and RPE convergence per arm.
 */
public class ExpectancyState implements PersistenceState {

    private final UUID id = UUID.randomUUID();

    private long creatureKey;
    private long cycle;
    private String mode;
    private String drive;
    private double driveLevel;
    private String target;
    private ActionType action;
    private double expected;
    private double reward;
    private double rpe;

    public ExpectancyState() { }

    public ExpectancyState(long creatureKey, long cycle, String mode, String drive, double driveLevel,
                           String target, ActionType action, double expected, double reward, double rpe) {
        this.creatureKey = creatureKey;
        this.cycle = cycle;
        this.mode = mode;
        this.drive = drive;
        this.driveLevel = driveLevel;
        this.target = target;
        this.action = action;
        this.expected = expected;
        this.reward = reward;
        this.rpe = rpe;
    }

    public UUID getId() { return id; }
    public long getCreatureKey() { return creatureKey; }
    public long getCycle() { return cycle; }
    public String getMode() { return mode; }
    public String getDrive() { return drive; }
    public double getDriveLevel() { return driveLevel; }
    public String getTarget() { return target; }
    public ActionType getAction() { return action; }
    public double getExpected() { return expected; }
    public double getReward() { return reward; }
    public double getRpe() { return rpe; }
}
