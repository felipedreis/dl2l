package br.cefetmg.lsi.l2l.creature.bd;

import javax.persistence.*;

@Entity
@Table(name = "consolidation_batch_stat", schema = "data")
@NamedNativeQuery(
    name = "ConsolidationBatchStat.getForCreature",
    query = "SELECT cbs.creature_key, cbs.onset_cycle, cbs.batch_index, cbs.batch_size, cbs.loss " +
            "FROM data.consolidation_batch_stat cbs " +
            "WHERE cbs.creature_key = ? ORDER BY cbs.onset_cycle, cbs.batch_index"
)
public class ConsolidationBatchStat implements PersistenceState {

    @Id @GeneratedValue
    private int id;

    @Column(name = "creature_key")
    private long creatureKey;

    @Column(name = "onset_cycle")
    private long onsetCycle;

    @Column(name = "batch_index")
    private int batchIndex;

    @Column(name = "batch_size")
    private int batchSize;

    @Column(name = "loss")
    private float loss;

    public ConsolidationBatchStat() { }

    public ConsolidationBatchStat(long creatureKey, long onsetCycle, int batchIndex, int batchSize, float loss) {
        this.creatureKey = creatureKey;
        this.onsetCycle = onsetCycle;
        this.batchIndex = batchIndex;
        this.batchSize = batchSize;
        this.loss = loss;
    }

    public int getId()           { return id; }
    public long getCreatureKey() { return creatureKey; }
    public long getOnsetCycle()  { return onsetCycle; }
    public int getBatchIndex()   { return batchIndex; }
    public int getBatchSize()    { return batchSize; }
    public float getLoss()       { return loss; }
}
