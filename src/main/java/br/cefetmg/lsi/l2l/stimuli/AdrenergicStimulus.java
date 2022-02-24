package br.cefetmg.lsi.l2l.stimuli;

import br.cefetmg.lsi.l2l.common.SequentialId;

/**
 * Created by felipe on 02/05/17.
 */
public class AdrenergicStimulus extends Stimulus {

    public final double delta;

    public AdrenergicStimulus(SequentialId origin, SequentialId stimulusId, double delta) {
        super(origin, stimulusId);
        this.delta = delta;
    }
}
