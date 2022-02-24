package br.cefetmg.lsi.l2l.stimuli;

import br.cefetmg.lsi.l2l.common.Constants;
import br.cefetmg.lsi.l2l.common.SequentialId;

/**
 * Created by felipe on 31/10/17.
 */
public class CholinergicStimulus extends Stimulus {

    public final double delta;

    public CholinergicStimulus(SequentialId origin, SequentialId stimulusId) {
        super(origin, stimulusId);
        delta = Constants.CHOLINERGIC_DELTA;
    }
}
