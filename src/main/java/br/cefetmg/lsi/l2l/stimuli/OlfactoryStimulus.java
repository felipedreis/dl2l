package br.cefetmg.lsi.l2l.stimuli;

import br.cefetmg.lsi.l2l.common.SequentialId;
import br.cefetmg.lsi.l2l.world.WorldObjectType;

/**
 * Created by felipe on 26/10/17.
 */
public class OlfactoryStimulus extends Stimulus {
    public final WorldObjectType objectType;
    public final double distance;
    public final double angle;

    public OlfactoryStimulus(SequentialId origin, SequentialId stimulusId, WorldObjectType objectType, double distance,
                             double angle) {
        super(origin, stimulusId);
        this.objectType = objectType;
        this.distance = distance;
        this.angle = angle;
    }
}
