package br.cefetmg.lsi.l2l.creature.bd;

import br.cefetmg.lsi.l2l.common.SequentialId;

import java.util.UUID;

/**
 * Plan A.3 (JEPA transition log): one row per decision cycle, written in the same batch as that cycle's
 * {@link ArbitrationDecisionState}. {@code h} is the four drive levels at decision time in
 * {@code FocusTracker.DRIVES} order (hunger, sleep, pain, tedium). Rows are immutable; the arrays are copied.
 * Written only with {@code transitionLoggingEnabled}.
 */
public class DecisionStepState implements PersistenceState {

    private final UUID id = UUID.randomUUID();

    private final long creatureKey;
    private final long cycle;
    private final String action;
    private final String objectType;
    private final Long targetKey;
    private final Long targetSequential;
    private final double[] h;
    private final String focalDrive;

    /**
     * @param target the executed action's target, {@code null} when the action has none
     * @param h      drive levels at decision time, four entries; copied
     */
    public DecisionStepState(long creatureKey, long cycle, String action, String objectType, SequentialId target,
                             double[] h, String focalDrive) {
        if (h == null || h.length != 4) {
            throw new IllegalArgumentException("h must have four drive levels");
        }
        this.creatureKey = creatureKey;
        this.cycle = cycle;
        this.action = action;
        this.objectType = objectType;
        this.targetKey = target == null ? null : target.key;
        this.targetSequential = target == null ? null : target.sequential;
        this.h = h.clone();
        this.focalDrive = focalDrive;
    }

    public UUID getId() { return id; }
    public long getCreatureKey() { return creatureKey; }
    public long getCycle() { return cycle; }
    public String getAction() { return action; }
    public String getObjectType() { return objectType; }
    public Long getTargetKey() { return targetKey; }
    public Long getTargetSequential() { return targetSequential; }
    public double getH(int i) { return h[i]; }
    public String getFocalDrive() { return focalDrive; }
}
