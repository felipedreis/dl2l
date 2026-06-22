package br.cefetmg.lsi.l2l.creature.bd;

import javax.persistence.*;

@Entity
@Table(name = "regulation_batch_stat", schema = "data")
@NamedNativeQueries({
    @NamedNativeQuery(name = "RegulationBatchStat.countByRegulatingCount",
        query = "select rbs.regulating_count as c, count(*) as n " +
                "from data.regulation_batch_stat rbs " +
                "inner join data.change_stimulus_state css on rbs.changestimulusstate_id = css.id " +
                "where css.key = ? group by rbs.regulating_count order by c"),
    @NamedNativeQuery(name = "RegulationBatchStat.sameDriveCollisions",
        query = "select count(*) from data.regulation_batch_stat rbs " +
                "inner join data.change_stimulus_state css on rbs.changestimulusstate_id = css.id " +
                "where css.key = ? and rbs.same_drive_collision = true")
})
public class RegulationBatchStat implements PersistenceState {

    @Id @GeneratedValue
    private int id;

    private int batchSize;
    private int regulatingCount;
    private boolean sameDriveCollision;
    // bit0=HUNGER, bit1=SLEEP; AdrenergicStimulus sets both bits
    private int drivesTouchedMask;

    @JoinColumn
    @OneToOne(cascade = {CascadeType.ALL})
    private ChangeStimulusState changeStimulusState;

    public RegulationBatchStat() { }

    public RegulationBatchStat(int batchSize, int regulatingCount, boolean sameDriveCollision,
                               int drivesTouchedMask, ChangeStimulusState changeStimulusState) {
        this.batchSize = batchSize;
        this.regulatingCount = regulatingCount;
        this.sameDriveCollision = sameDriveCollision;
        this.drivesTouchedMask = drivesTouchedMask;
        this.changeStimulusState = changeStimulusState;
    }

    public int getId() { return id; }
    public int getBatchSize() { return batchSize; }
    public int getRegulatingCount() { return regulatingCount; }
    public boolean isSameDriveCollision() { return sameDriveCollision; }
    public int getDrivesTouchedMask() { return drivesTouchedMask; }
    public ChangeStimulusState getChangeStimulusState() { return changeStimulusState; }
}
