package br.cefetmg.lsi.l2l.creature.bd;

import javax.persistence.*;

/**
 * Time series of tonic neuromodulator concentrations (dopamine, serotonin, orexin) and the
 * circadian phase published by {@code NeuromodulatorSystem}. Having the circadian phase here
 * means all four signals share one table, so the validation analysis can overlay them against
 * the base oscillator without joining additional tables. {@code seq} is a monotonic per-component
 * counter.
 */
@Entity
@Table(name = "neuromodulator_state_log", schema = "data")
@NamedNativeQueries({
    @NamedNativeQuery(name = "NeuromodulatorStateLog.getForCreature",
        query = "SELECT n.creature_key, n.seq, n.dopamine, n.serotonin, n.orexin, n.circadian_phase " +
                "FROM data.neuromodulator_state_log n " +
                "WHERE n.creature_key = ? ORDER BY n.seq")
})
public class NeuromodulatorStateLog implements PersistenceState {

    @Id @GeneratedValue
    private int id;

    @Column(name = "creature_key")
    private long creatureKey;

    @Column(name = "seq")
    private long seq;

    @Column(name = "dopamine")
    private double dopamine;

    @Column(name = "serotonin")
    private double serotonin;

    @Column(name = "orexin")
    private double orexin;

    @Column(name = "circadian_phase")
    private double circadianPhase;

    public NeuromodulatorStateLog() { }

    public NeuromodulatorStateLog(long creatureKey, long seq,
                                  double dopamine, double serotonin,
                                  double orexin, double circadianPhase) {
        this.creatureKey    = creatureKey;
        this.seq            = seq;
        this.dopamine       = dopamine;
        this.serotonin      = serotonin;
        this.orexin         = orexin;
        this.circadianPhase = circadianPhase;
    }
}
