package br.cefetmg.lsi.l2l.stimuli;

import br.cefetmg.lsi.l2l.common.SequentialId;

/**
 * Created by felipe on 02/01/17.
 */
public class FocusStimulus extends Stimulus {

    public final double focus;
    public final double angle;

    public FocusStimulus(SequentialId origin, SequentialId stimulusId, double focus, double angle) {
        super(origin, stimulusId);
        this.focus = focus;
        this.angle = angle;
    }
}
