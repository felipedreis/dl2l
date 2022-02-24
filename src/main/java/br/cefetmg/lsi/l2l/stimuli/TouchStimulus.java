package br.cefetmg.lsi.l2l.stimuli;

import br.cefetmg.lsi.l2l.common.SequentialId;
import br.cefetmg.lsi.l2l.world.WorldObjectType;

/**
 * Created by felipe on 03/01/17.
 */
public class TouchStimulus extends Stimulus {
    public final WorldObjectType emitter;
    public TouchStimulus(SequentialId origin, SequentialId stimulusId, WorldObjectType emitter) {
        super(origin, stimulusId);
        this.emitter = emitter;
    }
}
