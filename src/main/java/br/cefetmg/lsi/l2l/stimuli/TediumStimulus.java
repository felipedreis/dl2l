package br.cefetmg.lsi.l2l.stimuli;

import br.cefetmg.lsi.l2l.common.SequentialId;
import br.cefetmg.lsi.l2l.creature.common.ActionType;

public class TediumStimulus extends Stimulus {

    /** Positive = tedium rises; negative = tedium falls. */
    public final double delta;
    /** The action that produced this tedium change; used for EvaluationStimulus routing. */
    public final ActionType action;

    public TediumStimulus(SequentialId origin, SequentialId stimulusId, double delta, ActionType action) {
        super(origin, stimulusId);
        this.delta  = delta;
        this.action = action;
    }
}
