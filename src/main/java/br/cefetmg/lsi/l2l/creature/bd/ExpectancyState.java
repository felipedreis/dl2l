package br.cefetmg.lsi.l2l.creature.bd;

import br.cefetmg.lsi.l2l.creature.common.ActionType;

import javax.persistence.*;

/**
 * One reward-prediction event from Valuation (the VTA/SNc role): the expected reward, the observed
 * reward, and the resulting prediction error, tagged with the dominant drive, its level, and the
 * expectancy variant in use. This is the primary data for the issue-57 experiment — prediction
 * accuracy (expected vs reward) and RPE convergence per arm.
 */
@Entity
@Table(name = "expectancy_state", schema = "data")
@NamedNativeQueries({
    @NamedNativeQuery(name = "ExpectancyState.getForCreature",
        query = "SELECT es.creature_key, es.cycle, es.mode, es.drive, es.drive_level, " +
                "es.target, es.action, es.expected, es.reward, es.rpe " +
                "FROM data.expectancy_state es " +
                "WHERE es.creature_key = ? ORDER BY es.cycle")
})
public class ExpectancyState implements PersistenceState {

    @Id @GeneratedValue
    private int id;

    @Column(name = "creature_key")
    private long creatureKey;

    @Column(name = "cycle")
    private long cycle;

    @Column(name = "mode")
    private String mode;

    @Column(name = "drive")
    private String drive;

    @Column(name = "drive_level")
    private double driveLevel;

    @Column(name = "target")
    private String target;

    @Enumerated(EnumType.STRING)
    @Column(name = "action")
    private ActionType action;

    @Column(name = "expected")
    private double expected;

    @Column(name = "reward")
    private double reward;

    @Column(name = "rpe")
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
}
