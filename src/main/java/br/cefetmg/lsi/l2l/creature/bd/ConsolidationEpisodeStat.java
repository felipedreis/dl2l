package br.cefetmg.lsi.l2l.creature.bd;

import javax.persistence.*;

@Entity
@Table(name = "consolidation_episode_stat", schema = "data")
@NamedNativeQuery(
    name = "ConsolidationEpisodeStat.getForCreature",
    query = "SELECT ces.creature_key, ces.onset_cycle, ces.engram_count, " +
            "ces.mean_eligibility, ces.std_eligibility, ces.batches_completed, ces.aborted " +
            "FROM data.consolidation_episode_stat ces " +
            "WHERE ces.creature_key = ? ORDER BY ces.onset_cycle"
)
public class ConsolidationEpisodeStat implements PersistenceState {

    @Id @GeneratedValue
    private int id;

    @Column(name = "creature_key")
    private long creatureKey;

    @Column(name = "onset_cycle")
    private long onsetCycle;

    @Column(name = "engram_count")
    private int engramCount;

    @Column(name = "mean_eligibility")
    private double meanEligibility;

    @Column(name = "std_eligibility")
    private double stdEligibility;

    @Column(name = "batches_completed")
    private int batchesCompleted;

    @Column(name = "aborted")
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

    public int getId()               { return id; }
    public long getCreatureKey()     { return creatureKey; }
    public long getOnsetCycle()      { return onsetCycle; }
    public int getEngramCount()      { return engramCount; }
    public double getMeanEligibility(){ return meanEligibility; }
    public double getStdEligibility() { return stdEligibility; }
    public int getBatchesCompleted() { return batchesCompleted; }
    public boolean isAborted()       { return aborted; }
}
