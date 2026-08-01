package br.cefetmg.lsi.l2l.creature.bd;

import java.util.UUID;

public class ConsolidationBatchStat implements PersistenceState {

    private final UUID id = UUID.randomUUID();

    private long creatureKey;
    private long onsetCycle;
    private int batchIndex;
    private int batchSize;
    private float loss;

    public ConsolidationBatchStat() { }

    public ConsolidationBatchStat(long creatureKey, long onsetCycle, int batchIndex, int batchSize, float loss) {
        this.creatureKey = creatureKey;
        this.onsetCycle = onsetCycle;
        this.batchIndex = batchIndex;
        this.batchSize = batchSize;
        this.loss = loss;
    }

    public UUID getId()          { return id; }
    public long getCreatureKey() { return creatureKey; }
    public long getOnsetCycle()  { return onsetCycle; }
    public int getBatchIndex()   { return batchIndex; }
    public int getBatchSize()    { return batchSize; }
    public float getLoss()       { return loss; }
}
