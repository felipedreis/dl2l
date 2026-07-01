package br.cefetmg.lsi.l2l.creature.bd;

import javax.persistence.*;

@Entity
@Table(name = "memory_trace_stat", schema = "data")
public class MemoryTraceStat implements PersistenceState {

    @Id @GeneratedValue
    private int id;

    @Column(name = "creature_key")
    private long creatureKey;

    @Column(name = "onset_cycle")
    private long onsetCycle;

    @Column(name = "engram_count")
    private int engramCount;

    @Column(name = "groups_consolidated")
    private int groupsConsolidated;

    public MemoryTraceStat() {}

    public MemoryTraceStat(long creatureKey, long onsetCycle, int engramCount, int groupsConsolidated) {
        this.creatureKey        = creatureKey;
        this.onsetCycle         = onsetCycle;
        this.engramCount        = engramCount;
        this.groupsConsolidated = groupsConsolidated;
    }

    public int  getId()                 { return id; }
    public long getCreatureKey()        { return creatureKey; }
    public long getOnsetCycle()         { return onsetCycle; }
    public int  getEngramCount()        { return engramCount; }
    public int  getGroupsConsolidated() { return groupsConsolidated; }
}
