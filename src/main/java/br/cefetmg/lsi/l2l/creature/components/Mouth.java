package br.cefetmg.lsi.l2l.creature.components;

import br.cefetmg.lsi.l2l.common.SequentialId;
import br.cefetmg.lsi.l2l.creature.bd.ChangeStimulusState;
import br.cefetmg.lsi.l2l.creature.bd.ChangeStimulusStateBuilder;
import br.cefetmg.lsi.l2l.creature.bd.MouthInteractionState;
import br.cefetmg.lsi.l2l.creature.bd.MouthInteractionType;
import br.cefetmg.lsi.l2l.creature.common.ActionType;
import br.cefetmg.lsi.l2l.stimuli.*;

import java.util.List;

/**
 * Created by felipe on 02/01/17.
 */
public class Mouth extends CreatureComponent{

    public Mouth(SequentialId id) {
        super(id);
    }

    @Override
    public void onReceive(Object message) {
        List stimuli = (List) message;

        for(Object aStimuli : stimuli) {
            Stimulus stimulus = (Stimulus) aStimuli;

            if (stimulus instanceof SomaticStimulus) {
                SomaticStimulus somatic = (SomaticStimulus) stimulus;

                if(somatic.actionType == ActionType.EAT || somatic.actionType == ActionType.PLAY) {
                    DestructiveStimulus destructive = new DestructiveStimulus(id, somatic.target, nextStimulusId());
                    creature.holder().tell(destructive, self());
                }

            } else if(stimulus instanceof MechanicalStimulus) {
                MechanicalStimulus mechanical = (MechanicalStimulus) stimulus;
                TouchStimulus touchStimulus = new TouchStimulus(mechanical.origin, nextStimulusId(),
                        mechanical.objectType);
                creature.sensoryCortex().tell(touchStimulus, self());
            } else if (stimulus instanceof EnergeticStimulus) {
                EnergeticStimulus energetic = (EnergeticStimulus) stimulus;
                NutritiveStimulus nutritive = new NutritiveStimulus(energetic.origin, nextStimulusId(),
                        energetic.objectType, energetic.nutritiveValue);

                creature.homeostatic().tell(nutritive, self());

                ChangeStimulusState change = new ChangeStimulusStateBuilder(this, id)
                        .buildOneReceivedOneEmitted(energetic, nutritive);
                MouthInteractionState mouthState = new MouthInteractionState(MouthInteractionType.EAT,
                        energetic.objectType.name(), energetic.origin, change);

                persist(change, mouthState);
            }
        }
    }
}
