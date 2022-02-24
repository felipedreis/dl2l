package br.cefetmg.lsi.l2l.creature.components;

import br.cefetmg.lsi.l2l.common.SequentialId;
import br.cefetmg.lsi.l2l.creature.bd.ChangeStimulusState;
import br.cefetmg.lsi.l2l.creature.bd.ChangeStimulusStateBuilder;
import br.cefetmg.lsi.l2l.creature.common.InteractionState;
import br.cefetmg.lsi.l2l.stimuli.*;

import java.util.List;

/**
 * Created by felipe on 02/01/17.
 */
public class SensoryCortex extends CreatureComponent {

    public SensoryCortex(SequentialId id) {
        super(id);
    }

    @Override
    public void onReceive(Object message) {
        List<Stimulus> stimuli = (List) message;

        for(Stimulus stimulus : stimuli) {
            ProprioceptiveStimulus proprioceptive;

            if (stimulus instanceof VisualStimulus) {
                VisualStimulus visual = (VisualStimulus) stimulus;
                proprioceptive = new ProprioceptiveStimulus(visual.origin, nextStimulusId(), visual.emitter,
                        visual.origin, InteractionState.SEEING, visual.distance, visual.angle);

                creature.partialAppraisal().tell(proprioceptive, self());

                ChangeStimulusState change = new ChangeStimulusStateBuilder(this, id)
                        .buildOneReceivedOneEmitted(visual, proprioceptive);
                persist(change);

            } else if (stimulus instanceof TouchStimulus) {

                TouchStimulus touch = (TouchStimulus) stimulus;
                proprioceptive = new ProprioceptiveStimulus(touch.origin, nextStimulusId(), touch.emitter,
                        touch.origin, InteractionState.TOUCHING, 0, 0);

                creature.partialAppraisal().tell(proprioceptive, self());

                ChangeStimulusState change = new ChangeStimulusStateBuilder(this, id)
                        .buildOneReceivedOneEmitted(touch, proprioceptive);
                persist(change);
            } else if (stimulus instanceof OlfactoryStimulus) {
                OlfactoryStimulus olfactory = (OlfactoryStimulus) stimulus;

                proprioceptive = new ProprioceptiveStimulus(olfactory.origin, nextStimulusId(), olfactory.objectType,
                        olfactory.origin, InteractionState.SMELLING, olfactory.distance, olfactory.angle);
                creature.partialAppraisal().tell(proprioceptive, self());

                ChangeStimulusState change = new ChangeStimulusStateBuilder(this, id)
                        .buildOneReceivedOneEmitted(olfactory, proprioceptive);
                persist(change);
            }
        }
    }
}
