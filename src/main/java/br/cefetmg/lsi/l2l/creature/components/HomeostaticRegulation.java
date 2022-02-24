package br.cefetmg.lsi.l2l.creature.components;

import br.cefetmg.lsi.l2l.common.Constants;
import br.cefetmg.lsi.l2l.common.SequentialId;
import br.cefetmg.lsi.l2l.creature.bd.ChangeStimulusState;
import br.cefetmg.lsi.l2l.creature.bd.ChangeStimulusStateBuilder;
import br.cefetmg.lsi.l2l.creature.bd.EmotionalState;
import br.cefetmg.lsi.l2l.creature.bd.InternalDynamicState;
import br.cefetmg.lsi.l2l.creature.common.ActionType;
import br.cefetmg.lsi.l2l.stimuli.*;
import br.cefetmg.lsi.l2l.world.Self;

import java.util.List;

/**
 * Created by felipe on 02/01/17.
 */
public class HomeostaticRegulation extends CreatureComponent {
    public HomeostaticRegulation(SequentialId id) {
        super(id);
    }

    @Override
    public void onReceive(Object message) {
        List stimuli = (List) message;
        for (Object aStimuli : stimuli) {
            Stimulus stimulus = (Stimulus) aStimuli;

            EmotionalState before = new EmotionalState();
            before.setHunger(creature.emotions().getLevel(Constants.HUNGER));
            before.setSleep(creature.emotions().getLevel(Constants.SLEEP));

            Stimulus emitted = null;

            if (stimulus instanceof AdrenergicStimulus) {
                AdrenergicStimulus adrenergic = (AdrenergicStimulus) stimulus;
                creature.emotions().regulateAll(adrenergic.delta);
            } if (stimulus instanceof NutritiveStimulus) {
                NutritiveStimulus nutritive = (NutritiveStimulus) stimulus;
                Emotion regulated = creature.emotions().regulate(Constants.HUNGER, -nutritive.nutritiveValue);

                emitted = new EvaluationStimulus(stimulus.origin, nextStimulusId(),
                        stimulus.origin, nutritive.objectType, ActionType.EAT, regulated, -nutritive.nutritiveValue);

                creature.valuation().tell(emitted, self());
            } if (stimulus instanceof CholinergicStimulus) {
                CholinergicStimulus cholinergic = (CholinergicStimulus) stimulus;
                Emotion regulated = creature.emotions().regulate(Constants.SLEEP, -cholinergic.delta);
                emitted = new EvaluationStimulus(stimulus.origin, nextStimulusId(), id, Self.get(), ActionType.SLEEP,
                    regulated, -cholinergic.delta);
            }


            EmotionalState after = new EmotionalState();
            after.setHunger(creature.emotions().getLevel(Constants.HUNGER));
            after.setSleep(creature.emotions().getLevel(Constants.SLEEP));

            ChangeStimulusState change = new ChangeStimulusStateBuilder(this, this.id)
                    .buildOneReceivedOneEmitted(stimulus, emitted);
            InternalDynamicState dynamicState = new InternalDynamicState(before, after, change);

            persist(change, dynamicState);
        }


    }
}
