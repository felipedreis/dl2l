package br.cefetmg.lsi.l2l.stimuli;

import br.cefetmg.lsi.l2l.common.SequentialId;
import br.cefetmg.lsi.l2l.creature.common.ActionType;
import br.cefetmg.lsi.l2l.creature.components.Emotion;
import br.cefetmg.lsi.l2l.world.WorldObjectType;

/**
 * Created by felipe on 16/08/17.
 */
public class EvaluationStimulus extends Stimulus {

    public final SequentialId objectId;

    public final WorldObjectType type;

    public final Emotion regulatedEmotion;

    public final double arousalVariation;

    public final ActionType executedAction;

    public EvaluationStimulus(SequentialId origin, SequentialId stimulusId, SequentialId objectId,
                              WorldObjectType type, ActionType executedAction, Emotion regulatedEmotion,
                              double arousalVariation) {
        super(origin, stimulusId);
        this.objectId = objectId;
        this.type = type;
        this.executedAction = executedAction;
        this.regulatedEmotion = regulatedEmotion;
        this.arousalVariation = arousalVariation;
    }
}
