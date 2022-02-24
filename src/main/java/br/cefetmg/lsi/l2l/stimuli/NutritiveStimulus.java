package br.cefetmg.lsi.l2l.stimuli;

import br.cefetmg.lsi.l2l.common.SequentialId;
import br.cefetmg.lsi.l2l.world.WorldObjectType;

/**
 * Created by felipe on 10/08/17.
 */
public class NutritiveStimulus extends Stimulus {

    public final WorldObjectType objectType;

    public final double nutritiveValue;

    public NutritiveStimulus(SequentialId origin, SequentialId stimulusId, WorldObjectType objectType, double nutritiveValue) {
        super(origin, stimulusId);
        this.objectType = objectType;
        this.nutritiveValue = nutritiveValue;
    }
}
