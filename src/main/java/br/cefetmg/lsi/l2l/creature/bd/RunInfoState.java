package br.cefetmg.lsi.l2l.creature.bd;

import java.util.UUID;

/**
 * One row per holder JVM describing how the run was set up (issue #95): the root seed every
 * random stream was derived from, and whether it was generated because the config gave none.
 * Written once from {@code Holder.preStart()}, so a trial's data carries its own seed even when
 * nobody recorded it elsewhere.
 */
public class RunInfoState implements PersistenceState {
    private final UUID id = UUID.randomUUID();

    private final long seed;
    private final boolean seedGenerated;
    private final String trialId;
    private final long startedAt;
    private final double focusThetaOn;
    private final double focusThetaOff;

    public RunInfoState(long seed, boolean seedGenerated, String trialId, long startedAt,
                        double focusThetaOn, double focusThetaOff) {
        this.seed = seed;
        this.seedGenerated = seedGenerated;
        this.trialId = trialId;
        this.startedAt = startedAt;
        this.focusThetaOn = focusThetaOn;
        this.focusThetaOff = focusThetaOff;
    }

    public UUID getId() { return id; }
    public long getSeed() { return seed; }
    public boolean isSeedGenerated() { return seedGenerated; }
    public String getTrialId() { return trialId; }
    public long getStartedAt() { return startedAt; }
    /** Focus tracker thresholds the run used (recorded whether or not the focal drive is enabled). */
    public double getFocusThetaOn() { return focusThetaOn; }
    public double getFocusThetaOff() { return focusThetaOff; }
}
