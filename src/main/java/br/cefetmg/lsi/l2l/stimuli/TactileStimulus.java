package br.cefetmg.lsi.l2l.stimuli;

import br.cefetmg.lsi.l2l.common.SequentialId;
import br.cefetmg.lsi.l2l.world.WorldObjectType;

/**
 * Created by felipe on 03/01/17.
 */
public class TactileStimulus extends Stimulus {

    private final WorldObjectType contactWith;

    public TactileStimulus(SequentialId origin, SequentialId stimulusId, WorldObjectType contactWith) {
        super(origin, stimulusId);
        this.contactWith = contactWith;
    }
}
