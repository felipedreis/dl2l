package br.cefetmg.lsi.l2l.stimuli;

import br.cefetmg.lsi.l2l.common.SequentialId;
import br.cefetmg.lsi.l2l.creature.common.ActionType;
import br.cefetmg.lsi.l2l.world.WorldObjectType;

public class AnalgesicStimulus extends Stimulus {

    public final double delta;
    /** Null for passive pain decay; non-null for deliberate healing (e.g. eating Aloe). */
    public final ActionType action;
    /** Null for passive pain decay. */
    public final WorldObjectType objectType;

    public AnalgesicStimulus(SequentialId origin, SequentialId stimulusId,
                             double delta, ActionType action, WorldObjectType objectType) {
        super(origin, stimulusId);
        this.delta      = delta;
        this.action     = action;
        this.objectType = objectType;
    }
}
