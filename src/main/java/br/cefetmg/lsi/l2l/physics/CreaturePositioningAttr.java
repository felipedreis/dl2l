package br.cefetmg.lsi.l2l.physics;

import br.cefetmg.lsi.l2l.common.Constants;
import br.cefetmg.lsi.l2l.common.Point;
import br.cefetmg.lsi.l2l.common.SequentialId;

import java.io.Serializable;

/**
 * Created by felipe on 06/01/17.
 */
public class CreaturePositioningAttr implements Serializable{

    public final SequentialId creatureId;

    public final SequentialId bodyId;
    public final SequentialId eyeId;
    public final SequentialId mouthId;
    public final SequentialId noseId;

    public final Point position;

    public final double visionFieldPosition;
    public final double visionFieldOpening;
    public final double olfactoryFieldRadius;

    public final double bodyRadius = Constants.DEFAULT_BODY_RADIUS;

    public final boolean salivating;
    public final boolean shock;

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
        this.creatureId = creatureId;
        this.bodyId = bodyId;
        this.eyeId = eyeId;
        this.noseId = noseId;
        this.mouthId = mouthId;
        this.position = position;

        this.visionFieldPosition = visionFieldPosition;
        this.visionFieldOpening = visionFieldOpening;
        this.olfactoryFieldRadius = olfactoryFieldRadius;
        this.salivating = salivating;
        this.shock = shock;
    }
}
