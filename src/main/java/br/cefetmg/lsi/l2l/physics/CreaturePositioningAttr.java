package br.cefetmg.lsi.l2l.physics;

import akka.actor.ActorRef;
import br.cefetmg.lsi.l2l.common.Constants;
import br.cefetmg.lsi.l2l.common.Point;
import br.cefetmg.lsi.l2l.common.SequentialId;

import java.io.Serializable;

/**
 * Created by felipe on 06/01/17.
 */
public class CreaturePositioningAttr implements Serializable {

    public final SequentialId creatureId;

    public final SequentialId bodyId;
    public final SequentialId eyeId;
    public final SequentialId mouthId;
    public final SequentialId noseId;

    /** ActorRef of the creature itself — used by the collision detector to check liveness. */
    public final ActorRef creatureRef;

    /** Component ActorRefs so the collision detector can route stimuli across JVMs without TypedActor. */
    public final ActorRef bodyRef;
    public final ActorRef eyeRef;
    public final ActorRef mouthRef;
    public final ActorRef noseRef;

    public final Point position;

    public final double visionFieldPosition;
    public final double visionFieldOpening;
    public final double olfactoryFieldRadius;

    public final double bodyRadius = Constants.DEFAULT_BODY_RADIUS;

    public final boolean salivating;
    public final boolean shock;

    /** Convenience constructor for unit tests — all ActorRef fields are null. */
    public CreaturePositioningAttr(
            SequentialId creatureId,
            SequentialId bodyId,
            SequentialId eyeId,
            SequentialId noseId,
            SequentialId mouthId,
            Point position,
            double visionFieldPosition,
            double visionFieldOpening,
            double olfactoryFieldRadius,
            boolean salivating, boolean shock) {
        this(creatureId, bodyId, eyeId, noseId, mouthId,
             null, null, null, null, null,
             position, visionFieldPosition, visionFieldOpening, olfactoryFieldRadius,
             salivating, shock);
    }

    public CreaturePositioningAttr(
            SequentialId creatureId,
            SequentialId bodyId,
            SequentialId eyeId,
            SequentialId noseId,
            SequentialId mouthId,
            ActorRef creatureRef,
            ActorRef bodyRef,
            ActorRef eyeRef,
            ActorRef noseRef,
            ActorRef mouthRef,
            Point position,

            double visionFieldPosition,
            double visionFieldOpening,
            double olfactoryFieldRadius,

            boolean salivating, boolean shock) {
        this.creatureId = creatureId;
        this.bodyId = bodyId;
        this.eyeId = eyeId;
        this.noseId = noseId;
        this.mouthId = mouthId;
        this.creatureRef = creatureRef;
        this.bodyRef = bodyRef;
        this.eyeRef = eyeRef;
        this.noseRef = noseRef;
        this.mouthRef = mouthRef;
        this.position = position;

        this.visionFieldPosition = visionFieldPosition;
        this.visionFieldOpening = visionFieldOpening;
        this.olfactoryFieldRadius = olfactoryFieldRadius;
        this.salivating = salivating;
        this.shock = shock;
    }
}
