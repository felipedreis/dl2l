package br.cefetmg.lsi.l2l.creature.components;

import br.cefetmg.lsi.l2l.common.Point;
import br.cefetmg.lsi.l2l.common.SequentialId;
import br.cefetmg.lsi.l2l.creature.bd.ChangeStimulusState;
import br.cefetmg.lsi.l2l.creature.bd.ChangeStimulusStateBuilder;
import br.cefetmg.lsi.l2l.creature.bd.NoseState;
import br.cefetmg.lsi.l2l.creature.bd.ObjectSmeltState;
import br.cefetmg.lsi.l2l.stimuli.OlfactoryStimulus;
import br.cefetmg.lsi.l2l.stimuli.SmellStimulus;

import java.util.List;

/**
 * Created by felipe on 24/01/17.
 */
public class Nose extends CreatureComponent {

    public Nose(SequentialId id) {
        super(id);
    }

    @Override
    public void onReceive(Object message) {
        List stimuli = (List) message;

        for (Object aStimuli : stimuli) {
            if (aStimuli instanceof SmellStimulus) {
                SmellStimulus smell = (SmellStimulus) aStimuli;

                Point creaturePosition = creature.getPosition();


                OlfactoryStimulus olfactory = new OlfactoryStimulus(smell.origin, nextStimulusId(), smell.objectType,
                        creaturePosition.distance(smell.point), creaturePosition.angleAlpha(smell.point));

                creature.sensoryCortex().tell(olfactory, self());
                ChangeStimulusState change = new ChangeStimulusStateBuilder(this, id)
                        .buildOneReceivedOneEmitted(smell, olfactory);
                ObjectSmeltState smelt = new ObjectSmeltState(smell.origin, smell.objectType);
                smelt.setChangeStimulusState(change);
                persist(change, smelt);

            }
        }
    }
}
