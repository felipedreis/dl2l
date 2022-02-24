package br.cefetmg.lsi.l2l.stimuli;

import br.cefetmg.lsi.l2l.common.SequentialId;
import br.cefetmg.lsi.l2l.creature.common.InteractionState;
import br.cefetmg.lsi.l2l.world.WorldObjectType;

/**
 * Created by felipe on 13/03/17.
 */
public class ProprioceptiveStimulus extends Stimulus {

    private final WorldObjectType objectType;

    private final SequentialId targetId;

    private final InteractionState state;

    private final double distance;

    private final double angle;

    public ProprioceptiveStimulus(SequentialId origin, SequentialId stimulusId, WorldObjectType objectType,
                                  SequentialId targetId, InteractionState state, double distance, double angle) {
        super(origin, stimulusId);
        this.objectType = objectType;
        this.targetId = targetId;
        this.state = state;
        this.distance = distance;
        this.angle = angle;
    }

    public WorldObjectType getObjectType() {
        return objectType;
    }

    public SequentialId getTargetId() {
        return targetId;
    }

    public InteractionState getState() {
        return state;
    }

    public double getDistance() {
        return distance;
    }

    public double getAngle() {
        return angle;
    }
}
