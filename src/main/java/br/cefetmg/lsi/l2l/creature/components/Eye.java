package br.cefetmg.lsi.l2l.creature.components;

import br.cefetmg.lsi.l2l.common.SequentialId;
import br.cefetmg.lsi.l2l.creature.bd.ChangeStimulusState;
import br.cefetmg.lsi.l2l.creature.bd.ChangeStimulusStateBuilder;
import br.cefetmg.lsi.l2l.creature.bd.EyeState;
import br.cefetmg.lsi.l2l.creature.bd.ObjectSeenState;
import br.cefetmg.lsi.l2l.stimuli.FocusStimulus;
import br.cefetmg.lsi.l2l.stimuli.LuminousStimulus;
import br.cefetmg.lsi.l2l.stimuli.Stimulus;
import br.cefetmg.lsi.l2l.stimuli.VisualStimulus;

import java.util.List;

/**
 * Created by felipe on 02/01/17.
 */
public class Eye extends CreatureComponent {

    public Eye(SequentialId id) {
        super(id);
    }

    public void onReceive(Object message) {

        List stimuli = (List) message;

        for (Object aStimulus : stimuli) {
            Stimulus stimulus = (Stimulus) aStimulus;
            ChangeStimulusState change;

            if (stimulus instanceof LuminousStimulus) {
                LuminousStimulus luminous = (LuminousStimulus) stimulus;
                double angle = creature.getPosition().angleAlpha(luminous.getPoint());
                double distance = creature.getPosition().distance(luminous.getPoint());
                VisualStimulus visual = new VisualStimulus(luminous.origin,
                        nextStimulusId(),
                        luminous.getObjectType(),
                        angle,
                        distance,
                        creature.getVisionFieldPosition() + creature.getVisionFieldOpening()/2.0);

                logger.fine(String.format("Eye[%s]: saw %s angle=%.3f dist=%.1f",
                        id, luminous.getObjectType(), angle, distance));

                creature.sensoryCortex().tell(visual);

                change = new ChangeStimulusStateBuilder(this, this.id)
                        .buildOneReceivedOneEmitted(luminous, visual);

                ObjectSeenState seen = new ObjectSeenState(change, visual.emitter, visual.origin,
                        visual.distance, visual.angle, visual.direction);

                persist(change, seen);
            } else if (stimulus instanceof FocusStimulus) {
                double lastFocus = creature.getVisionFieldOpening(), lastPosition = creature.getVisionFieldPosition();
                FocusStimulus focus = (FocusStimulus) stimulus;

                creature.setVisionFieldOpening(focus.focus);
                creature.setVisionFieldPosition(focus.angle);

                change = new ChangeStimulusStateBuilder(this, this.id)
                        .buildOneReceivedOneEmitted(focus, null);

                EyeState eyeState = new EyeState(change, lastPosition, lastFocus, focus.focus, focus.angle);

                persist(change, eyeState);
            }
        }
    }
}
