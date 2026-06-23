package br.cefetmg.lsi.l2l.creature.bd;

import javax.persistence.*;

@Entity
@Table(name = "sleep_episode_state", schema = "data")
@NamedNativeQuery(
    name = "SleepEpisodeState.getForCreature",
    query = "SELECT ses.creature_key, ses.onset_cycle, ses.wake_cycle, ses.duration_ticks " +
            "FROM data.sleep_episode_state ses " +
            "WHERE ses.creature_key = ? ORDER BY ses.onset_cycle"
)
public class SleepEpisodeState implements PersistenceState {

    @Id @GeneratedValue
    private int id;

    @Column(name = "creature_key")
    private long creatureKey;

    @Column(name = "onset_cycle")
    private long onsetCycle;

    @Column(name = "wake_cycle")
    private long wakeCycle;

    @Column(name = "duration_ticks")
    private int durationTicks;

    public SleepEpisodeState() { }

    public SleepEpisodeState(long creatureKey, long onsetCycle, long wakeCycle, int durationTicks) {
        this.creatureKey = creatureKey;
        this.onsetCycle = onsetCycle;
        this.wakeCycle = wakeCycle;
        this.durationTicks = durationTicks;
    }

    public int getId()           { return id; }
    public long getCreatureKey() { return creatureKey; }
    public long getOnsetCycle()  { return onsetCycle; }
    public long getWakeCycle()   { return wakeCycle; }
    public int getDurationTicks(){ return durationTicks; }
}
