package br.cefetmg.lsi.l2l.creature.bd;

import java.util.UUID;

public class SleepEpisodeState implements PersistenceState {

    private final UUID id = UUID.randomUUID();

    private long creatureKey;
    private long onsetCycle;
    private long wakeCycle;
    private int durationTicks;

    public SleepEpisodeState() { }

    public SleepEpisodeState(long creatureKey, long onsetCycle, long wakeCycle, int durationTicks) {
        this.creatureKey = creatureKey;
        this.onsetCycle = onsetCycle;
        this.wakeCycle = wakeCycle;
        this.durationTicks = durationTicks;
    }

    public UUID getId()          { return id; }
    public long getCreatureKey() { return creatureKey; }
    public long getOnsetCycle()  { return onsetCycle; }
    public long getWakeCycle()   { return wakeCycle; }
    public int getDurationTicks(){ return durationTicks; }
}
