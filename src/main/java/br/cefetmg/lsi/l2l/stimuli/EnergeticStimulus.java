package br.cefetmg.lsi.l2l.stimuli;

import br.cefetmg.lsi.l2l.common.SequentialId;
import br.cefetmg.lsi.l2l.world.WorldObjectType;

/**
 * Created by felipe on 03/01/17.
 */
public class EnergeticStimulus extends Stimulus{

    public final double nutritiveValue;

    public final WorldObjectType objectType;

    public EnergeticStimulus(SequentialId origin, SequentialId stimulusId, double nutritiveValue,
                             WorldObjectType objectType) {
        super(origin, stimulusId);
        this.nutritiveValue = nutritiveValue;
        this.objectType = objectType;
    }


}
