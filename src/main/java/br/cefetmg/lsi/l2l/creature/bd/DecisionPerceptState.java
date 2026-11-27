package br.cefetmg.lsi.l2l.creature.bd;

import java.util.UUID;

/**
 * Plan A.3 (JEPA transition log): one perception of a decision cycle's candidate set, at its rank in that set.
 * {@code contact} follows {@code EngramKeyEncoder}: not the creature itself, and within the object's contact
 * distance. Written in the same batch as the cycle's {@link ArbitrationDecisionState}, with the same cycle.
 * Rows are immutable. Written only with {@code transitionLoggingEnabled}.
 */
public class DecisionPerceptState implements PersistenceState {

    private final UUID id = UUID.randomUUID();

    private final long creatureKey;
    private final long cycle;
    private final int rank;
    private final Long objectKey;
    private final Long objectSequential;
    private final String objectType;
    private final double distance;
    private final double angle;
    private final boolean contact;

    public DecisionPerceptState(long creatureKey, long cycle, int rank, Long objectKey, Long objectSequential,
                                String objectType, double distance, double angle, boolean contact) {
        this.creatureKey = creatureKey;
        this.cycle = cycle;
        this.rank = rank;
        this.objectKey = objectKey;
        this.objectSequential = objectSequential;
        this.objectType = objectType;
        this.distance = distance;
        this.angle = angle;
        this.contact = contact;
    }

    public UUID getId() { return id; }
    public long getCreatureKey() { return creatureKey; }
    public long getCycle() { return cycle; }
    public int getRank() { return rank; }
    public Long getObjectKey() { return objectKey; }
    public Long getObjectSequential() { return objectSequential; }
    public String getObjectType() { return objectType; }
    public double getDistance() { return distance; }
    public double getAngle() { return angle; }
    public boolean isContact() { return contact; }
}
