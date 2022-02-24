package br.cefetmg.lsi.l2l.creature.components;

import br.cefetmg.lsi.l2l.common.SequentialId;
import br.cefetmg.lsi.l2l.creature.bd.ChangeStimulusState;
import br.cefetmg.lsi.l2l.creature.bd.ChangeStimulusStateBuilder;
import br.cefetmg.lsi.l2l.creature.common.ActionType;
import br.cefetmg.lsi.l2l.stimuli.*;

import java.util.Arrays;
import java.util.List;

/**
 * Created by felipe on 02/01/17.
 */
public class EffectorCortex extends CreatureComponent {
    public EffectorCortex(SequentialId id) {
        super(id);
    }

    @Override
    public void onReceive(Object message) {
        List stimuli = (List) message;

        for(Object aStimuli : stimuli) {
            Stimulus stimulus = (Stimulus) aStimuli;

            if(stimulus instanceof CorticalStimulus) {
                CorticalStimulus cortical = (CorticalStimulus) stimulus;
                SomaticStimulus somatic = null;
                if(cortical.action == ActionType.PLAY || cortical.action == ActionType.EAT) {
                    somatic = new SomaticStimulus(id, cortical.target, nextStimulusId(),
                            cortical.action);
                    creature.mouth().tell(somatic, self());
                }

                MuscularStimulus muscular = new MuscularStimulus(id, nextStimulusId(), cortical.speed, cortical.angle);
                FocusStimulus focus = new FocusStimulus(id, nextStimulusId(), cortical.focus, cortical.angle);

                creature.body().tell(muscular, self());
                creature.eye().tell(focus, self());

                ChangeStimulusState change = new ChangeStimulusStateBuilder(this, id)
                        .buildOneReceivedMultipleEmitted(cortical, Arrays.asList(somatic, muscular, focus));
                persist(change);
            }
        }
    }
}
