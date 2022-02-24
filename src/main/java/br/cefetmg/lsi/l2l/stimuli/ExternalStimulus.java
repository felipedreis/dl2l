package br.cefetmg.lsi.l2l.stimuli;

import br.cefetmg.lsi.l2l.common.SequentialId;

/**
 * Created by felipe on 27/03/17.
 */
public class ExternalStimulus extends Stimulus {
    public final SequentialId target;

    public ExternalStimulus(SequentialId origin, SequentialId target, SequentialId stimulusId) {
        super(origin, stimulusId);
        this.target = target;
    }

}
