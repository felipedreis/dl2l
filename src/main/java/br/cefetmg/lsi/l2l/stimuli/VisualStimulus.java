package br.cefetmg.lsi.l2l.stimuli;

import br.cefetmg.lsi.l2l.common.SequentialId;
import br.cefetmg.lsi.l2l.world.WorldObjectType;

/**
 * Created by felipe on 02/01/17.
 */
public class VisualStimulus extends Stimulus {

    public final WorldObjectType emitter;

    public final double angle;
    public final double distance;
    public final double direction;

    public VisualStimulus(SequentialId origin, SequentialId stimulusId, WorldObjectType emitter, double angle, double distance, double direction) {
        super(origin, stimulusId);
        this.emitter = emitter;
        this.angle = angle;
        this.distance = distance;
        this.direction = direction;
    }
}
