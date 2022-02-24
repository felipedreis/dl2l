package br.cefetmg.lsi.l2l.creature.components;

import akka.actor.ActorRef;
import br.cefetmg.lsi.l2l.common.Constants;
import br.cefetmg.lsi.l2l.common.SequentialId;
import br.cefetmg.lsi.l2l.creature.bd.BehaviouralEfficiencyState;
import br.cefetmg.lsi.l2l.creature.bd.ChangeStimulusState;
import br.cefetmg.lsi.l2l.creature.bd.ChangeStimulusStateBuilder;
import br.cefetmg.lsi.l2l.creature.bd.InternalDynamicState;
import br.cefetmg.lsi.l2l.creature.common.Perception;
import br.cefetmg.lsi.l2l.stimuli.AdrenergicStimulus;
import br.cefetmg.lsi.l2l.stimuli.EmotionalStimulus;
import br.cefetmg.lsi.l2l.stimuli.ProprioceptiveStimulus;
import br.cefetmg.lsi.l2l.stimuli.Stimulus;
import br.cefetmg.lsi.l2l.world.Self;
import scala.concurrent.duration.Duration;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Created by felipe on 02/01/17.
 */
public class PartialAppraisal extends CreatureComponent {

    public PartialAppraisal(SequentialId id) {
        super(id);
    }

    @Override
    public void preStart() throws Exception {
        super.preStart();

    }

    @Override
    public void onReceive(Object message) {
        List stimuli;

        if(message instanceof List)
            stimuli = (List) message;
        else
            stimuli = new ArrayList();

        Emotion maxEmotion = creature.emotions().getMaxArousal();

        if (maxEmotion.getLevel() >= Constants.MAX_AROUSAL_LEVEL)
            creature.kill();

        AdrenergicStimulus adrenergic = new AdrenergicStimulus(this.id, nextStimulusId(), Constants.DELTA);
        creature.homeostatic().tell(adrenergic, self());

        List<Stimulus> propStimuli = (List) stimuli.stream()
                .filter(s -> s instanceof ProprioceptiveStimulus)
                .collect(Collectors.toList());

        List<Perception> perceptions = propStimuli.stream()
                .map((Stimulus stimulus) -> {
                    ProprioceptiveStimulus proprioceptive = (ProprioceptiveStimulus) stimulus;

                    return new Perception(proprioceptive.getObjectType(), proprioceptive.getTargetId(),
                            proprioceptive.getDistance(), proprioceptive.getAngle());
                })
                .collect(Collectors.toList());

        if (perceptions.isEmpty()) {
            double visionFieldAngle = creature.getVisionFieldPosition();
            perceptions.add(new Perception(Self.get(), id.father(), 0, visionFieldAngle));
        }


        double behaviouralEfficiency = normalizedBehaviouralEfficiency(maxEmotion.getLevel(), perceptions.size());

        EmotionalStimulus emotional = new EmotionalStimulus(this.id, nextStimulusId(), perceptions, maxEmotion, behaviouralEfficiency);

        creature.fullAppraisal().tell(emotional, self());


        ChangeStimulusState changeEmotional = new ChangeStimulusStateBuilder(this, id)
                .buildMultipleReceivedOneEmitted(propStimuli, emotional);
        ChangeStimulusState changeAdrenergic = new ChangeStimulusStateBuilder(this, id)
                .buildOneReceivedOneEmitted(null, adrenergic);

        BehaviouralEfficiencyState behaviouralState = new BehaviouralEfficiencyState();
        behaviouralState.setBehaviouralEfficiency(behaviouralEfficiency);
        behaviouralState.setNumberOfObjects(perceptions.size());
        behaviouralState.setComplexTask(perceptions.size() < Constants.COMPLEX_TASK);
        behaviouralState.setChangeStimulusState(changeEmotional);

        persist(changeEmotional, changeAdrenergic, behaviouralState);
    }

    private double normalizedBehaviouralEfficiency(double arousal, int perceptionsCount) {
        double efficiency;
        if (arousal < Constants.MIN_AROUSAL_LEVEL) {
            efficiency = 5.55 * arousal / 90.0;
        } else if (perceptionsCount < Constants.COMPLEX_TASK) {
            efficiency = (arousal * (5.714  - (0.816 * arousal))) / 9.303;
        } else {
            efficiency =  ( 16 * (1 - Math.exp(-0.4 * arousal)) ) / 15.0;
        }

        return efficiency;
    }
}
