package br.cefetmg.lsi.l2l.creature.components;

import br.cefetmg.lsi.l2l.common.Point;
import br.cefetmg.lsi.l2l.common.SequentialId;
import br.cefetmg.lsi.l2l.common.Vector;
import br.cefetmg.lsi.l2l.creature.bd.BodyState;
import br.cefetmg.lsi.l2l.creature.bd.ChangeStimulusState;
import br.cefetmg.lsi.l2l.creature.bd.ChangeStimulusStateBuilder;
import br.cefetmg.lsi.l2l.stimuli.MuscularStimulus;
import br.cefetmg.lsi.l2l.stimuli.Stimulus;

import java.util.List;

/**
 * Created by felipe on 02/01/17.
 */
public class Body extends CreatureComponent{

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

                logger.fine(String.format("Body[%s]: angle=%.3f speed=%.3f  (%.1f,%.1f)->(%.1f,%.1f)  vec(%.3f,%.3f)",
                        id, muscular.angle, muscular.speed,
                        currentPosition.x, currentPosition.y, nextPosition.x, nextPosition.y,
                        speedVector.x, speedVector.y));

                // CholinergicStimulus (sleep recovery) is no longer emitted here.
                // Speed=0 is a physics fact shared by EAT, OBSERVE, and SLEEP; gating sleep
                // recovery on speed alone caused EAT to clear sleep drive on every eating tick.
                // Sleep recovery is now emitted by FullAppraisal when ActionType.SLEEP is selected.
                ChangeStimulusState change = new ChangeStimulusStateBuilder(this, this.id)
                        .buildOneReceivedOneEmitted(muscular, null);
                BodyState bodyState = new BodyState(currentPosition.x, currentPosition.y, nextPosition.x, nextPosition.y,
                        speedVector.size());
                bodyState.setStimulusState(change);

                persist(change, bodyState);
            }
        }
    }
}
