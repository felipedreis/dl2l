package br.cefetmg.lsi.l2l.stimuli;

import br.cefetmg.lsi.l2l.common.SequentialId;
import br.cefetmg.lsi.l2l.world.WorldObjectType;

/**
 * Created by felipe on 05/05/17.
 */
public class MechanicalStimulus extends Stimulus {

    public final WorldObjectType objectType;

    public MechanicalStimulus(SequentialId origin, SequentialId stimulusId, WorldObjectType objectType) {
        super(origin, stimulusId);
        this.objectType = objectType;
    }
}
