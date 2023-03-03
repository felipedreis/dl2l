package br.cefetmg.lsi.l2l.creature.components;

import akka.actor.UntypedActor;
import br.cefetmg.lsi.l2l.common.Constants;
import br.cefetmg.lsi.l2l.common.Point;
import br.cefetmg.lsi.l2l.common.SequentialId;
import br.cefetmg.lsi.l2l.common.Vector;
import br.cefetmg.lsi.l2l.creature.bd.BodyState;
import br.cefetmg.lsi.l2l.creature.bd.ChangeStimulusState;
import br.cefetmg.lsi.l2l.creature.bd.ChangeStimulusStateBuilder;
import br.cefetmg.lsi.l2l.creature.bd.PersistenceState;
import br.cefetmg.lsi.l2l.stimuli.CholinergicStimulus;
import br.cefetmg.lsi.l2l.stimuli.MuscularStimulus;
import br.cefetmg.lsi.l2l.stimuli.Stimulus;

import java.util.List;

/**
 * Created by felipe on 02/01/17.
 */
public class Body extends CreatureComponent {

    public Body(SequentialId id) {
        super(id);
    }

    @Override
    public void onReceive(Object message) {
        List stimuli = (List) message;

        for(Object aStimuli :  stimuli) {
            Stimulus stimulus = (Stimulus) aStimuli;

            if(stimulus instanceof MuscularStimulus) {
                MuscularStimulus muscular = (MuscularStimulus) stimulus;
                Point currentPosition = creature.getPosition();

                Vector speedVector = Vector.fromPolar(muscular.angle, muscular.speed);
                Point nextPosition = currentPosition.move(speedVector);
                creature.setPosition(nextPosition);

                CholinergicStimulus cholinergic = null;
                if (muscular.speed  == 0) {
                    cholinergic = new CholinergicStimulus(id, nextStimulusId());
                    creature.homeostatic().tell(cholinergic, self());
                }

                ChangeStimulusState change = new ChangeStimulusStateBuilder(this, this.id)
                        .buildOneReceivedOneEmitted(muscular, cholinergic);
                BodyState bodyState = new BodyState(currentPosition.x, currentPosition.y, nextPosition.x, nextPosition.y,
                        speedVector.size());
                bodyState.setStimulusState(change);

                persist(change, bodyState);
            }
        }
    }
}
