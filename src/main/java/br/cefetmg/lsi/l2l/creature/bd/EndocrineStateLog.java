package br.cefetmg.lsi.l2l.creature.bd;

import javax.persistence.*;

/**
 * Time series of cortisol tonic level and resulting stress affect published by
 * {@code EndocrineSystem}. Used in the validation analysis to overlay cortisol against
 * the circadian phase (held in {@code NeuromodulatorStateLog}) and confirm the daily
 * morning pulse and stressor response are working as designed.
 */
@Entity
@Table(name = "endocrine_state_log", schema = "data")
@NamedNativeQueries({
    @NamedNativeQuery(name = "EndocrineStateLog.getForCreature",
        query = "SELECT e.creature_key, e.seq, e.cortisol_tonic, e.stress_level " +
                "FROM data.endocrine_state_log e " +
                "WHERE e.creature_key = ? ORDER BY e.seq")
})
public class EndocrineStateLog implements PersistenceState {

    @Id @GeneratedValue
    private int id;

    @Column(name = "creature_key")
    private long creatureKey;

    @Column(name = "seq")
    private long seq;

    @Column(name = "cortisol_tonic")
    private double cortisolTonic;

    @Column(name = "stress_level")
    private double stressLevel;

    public EndocrineStateLog() { }

    public EndocrineStateLog(long creatureKey, long seq, double cortisolTonic, double stressLevel) {
        this.creatureKey  = creatureKey;
        this.seq          = seq;
        this.cortisolTonic = cortisolTonic;
        this.stressLevel  = stressLevel;
    }
}
