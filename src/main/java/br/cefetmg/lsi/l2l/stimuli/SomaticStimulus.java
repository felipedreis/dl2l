package br.cefetmg.lsi.l2l.stimuli;

import br.cefetmg.lsi.l2l.common.SequentialId;
import br.cefetmg.lsi.l2l.creature.common.ActionType;

/**
 * Created by felipe on 03/01/17.
 */
public class SomaticStimulus extends Stimulus {

    public final SequentialId target;
    public final ActionType actionType;

    public SomaticStimulus(SequentialId origin, SequentialId target, SequentialId stimulusId, ActionType actionType) {
        super(origin, stimulusId);
        this.target = target;
        this.actionType = actionType;
    }
}
