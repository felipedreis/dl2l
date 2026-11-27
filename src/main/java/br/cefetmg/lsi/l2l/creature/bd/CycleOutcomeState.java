package br.cefetmg.lsi.l2l.creature.bd;

import java.util.UUID;

/**
 * Plan A.3 (JEPA transition log): one attributed per-act drive outcome K = (d_hunger, d_sleep, d_pain, d_tedium),
 * stamped with the decision cycle that the memory system held when the outcome was recorded. BASELINE drift is
 * never sent here. Rows are immutable; the array is copied. Written only with {@code transitionLoggingEnabled}.
 */
public class CycleOutcomeState implements PersistenceState {

    private final UUID id = UUID.randomUUID();

    private final long creatureKey;
    private final long cycle;
    private final double[] k;

    /** @param k the outcome in {@code FocusTracker.DRIVES} order, four entries; copied */
    public CycleOutcomeState(long creatureKey, long cycle, double[] k) {
        if (k == null || k.length != 4) {
            throw new IllegalArgumentException("k must have four drive changes");
        }
        this.creatureKey = creatureKey;
        this.cycle = cycle;
        this.k = k.clone();
    }

    public UUID getId() { return id; }
    public long getCreatureKey() { return creatureKey; }
    public long getCycle() { return cycle; }
    public double getD(int i) { return k[i]; }
}
