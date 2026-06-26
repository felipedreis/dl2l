package br.cefetmg.lsi.l2l.stimuli;

import br.cefetmg.lsi.l2l.common.SequentialId;
import br.cefetmg.lsi.l2l.creature.common.ActionType;
import br.cefetmg.lsi.l2l.world.WorldObjectType;

public class NociceptiveStimulus extends Stimulus {

    public final double painIntensity;
    /** The deliberate action that caused the pain; null for passive collision pain. */
    public final ActionType action;
    public final WorldObjectType objectType;

    public NociceptiveStimulus(SequentialId origin, SequentialId stimulusId,
                               double painIntensity, ActionType action, WorldObjectType objectType) {
        super(origin, stimulusId);
        this.painIntensity = painIntensity;
        this.action        = action;
        this.objectType    = objectType;
    }
}
