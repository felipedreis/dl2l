package br.cefetmg.lsi.l2l.creature.bd;

import java.util.UUID;

public class ConsolidationEpisodeStat implements PersistenceState {

    private final UUID id = UUID.randomUUID();

    private long creatureKey;
    private long onsetCycle;
    private int engramCount;
    private double meanEligibility;
    private double stdEligibility;
    private int batchesCompleted;
    private boolean aborted;

    public ConsolidationEpisodeStat() { }

    public ConsolidationEpisodeStat(long creatureKey, long onsetCycle, int engramCount,
                                    double meanEligibility, double stdEligibility,
                                    int batchesCompleted, boolean aborted) {
        this.creatureKey = creatureKey;
        this.onsetCycle = onsetCycle;
        this.engramCount = engramCount;
        this.meanEligibility = meanEligibility;
        this.stdEligibility = stdEligibility;
        this.batchesCompleted = batchesCompleted;
        this.aborted = aborted;
    }

    public UUID getId()              { return id; }
    public long getCreatureKey()     { return creatureKey; }
    public long getOnsetCycle()      { return onsetCycle; }
    public int getEngramCount()      { return engramCount; }
    public double getMeanEligibility(){ return meanEligibility; }
    public double getStdEligibility() { return stdEligibility; }
    public int getBatchesCompleted() { return batchesCompleted; }
    public boolean isAborted()       { return aborted; }
}
