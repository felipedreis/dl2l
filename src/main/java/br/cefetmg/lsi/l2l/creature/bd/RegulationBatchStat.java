package br.cefetmg.lsi.l2l.creature.bd;

import java.util.UUID;

public class RegulationBatchStat implements PersistenceState {

    private final UUID id = UUID.randomUUID();

    private int batchSize;
    private int regulatingCount;
    private boolean sameDriveCollision;
    // bit0=HUNGER, bit1=SLEEP; AdrenergicStimulus sets both bits
    private int drivesTouchedMask;

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

    public UUID getId() { return id; }
    public int getBatchSize() { return batchSize; }
    public int getRegulatingCount() { return regulatingCount; }
    public boolean isSameDriveCollision() { return sameDriveCollision; }
    public int getDrivesTouchedMask() { return drivesTouchedMask; }
    public ChangeStimulusState getChangeStimulusState() { return changeStimulusState; }
}
