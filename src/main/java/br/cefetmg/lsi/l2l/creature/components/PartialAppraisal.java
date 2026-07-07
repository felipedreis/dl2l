package br.cefetmg.lsi.l2l.creature.components;

import br.cefetmg.lsi.l2l.cluster.settings.LearningSettings;
import br.cefetmg.lsi.l2l.common.Constants;
import br.cefetmg.lsi.l2l.common.SequentialId;
import br.cefetmg.lsi.l2l.creature.bd.BehaviouralEfficiencyState;
import br.cefetmg.lsi.l2l.creature.bd.ChangeStimulusState;
import br.cefetmg.lsi.l2l.creature.bd.ChangeStimulusStateBuilder;
import br.cefetmg.lsi.l2l.creature.common.Perception;
import br.cefetmg.lsi.l2l.stimuli.AdrenergicStimulus;
import br.cefetmg.lsi.l2l.stimuli.EmotionalStimulus;
import br.cefetmg.lsi.l2l.stimuli.ProprioceptiveStimulus;
import br.cefetmg.lsi.l2l.stimuli.AdenosinergicStimulus;
import br.cefetmg.lsi.l2l.stimuli.NeuromodulatorTick;
import br.cefetmg.lsi.l2l.stimuli.SerotonergicStimulus;
import br.cefetmg.lsi.l2l.stimuli.Stimulus;
import br.cefetmg.lsi.l2l.world.Self;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Created by felipe on 02/01/17.
 */
public class PartialAppraisal extends CreatureComponent {

    private final LearningSettings learningSettings;
    private CircadianClock circadian;

    public PartialAppraisal(SequentialId id, LearningSettings learningSettings) {
        super(id);
        this.learningSettings = learningSettings;
    }

    @Override
    public void preStart() throws Exception {
        super.preStart();
        circadian = learningSettings.isCircadianEnabled() ? new ActiveCircadianClock() : new DisabledCircadianClock();
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
        creature.homeostatic().tell(adrenergic);

        circadian.tick();
        double sleepDriveRate = circadian.driveRate();
        if (sleepDriveRate > 0) {
            AdenosinergicStimulus sleepDrive = new AdenosinergicStimulus(this.id, nextStimulusId(), sleepDriveRate);
            creature.homeostatic().tell(sleepDrive);
        }

        // Neuromodulator pacemaker: tonic serotonin (satiety) release + per-cycle reuptake tick.
        // Emitted whenever the pool is in use (expectancy feeds dopamine; neuromodulation reads tonic).
        if (learningSettings.isNeuromodulationEnabled() || learningSettings.isExpectancyEnabled()) {
            creature.neuromodulators().tell(
                    new SerotonergicStimulus(this.id, nextStimulusId(), computeSatiety()));
            creature.neuromodulators().tell(
                    new NeuromodulatorTick(this.id, nextStimulusId(), circadian.phase()));
        }

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

        logger.info(String.format("PartialAppraisal[%s]: arousal=%.3f perceptions=%d behaviouralEfficiency=%.3f",
                id, maxEmotion.getLevel(), perceptions.size(), behaviouralEfficiency));
        perceptions.forEach(p -> logger.fine(String.format("PartialAppraisal[%s]:   perception type=%s angle=%.3f dist=%.1f",
                id, p.objectType, p.angle, p.distance)));

        EmotionalStimulus emotional = new EmotionalStimulus(this.id, nextStimulusId(), perceptions, maxEmotion, behaviouralEfficiency);

        creature.fullAppraisal().tell(emotional);


        ChangeStimulusState changeEmotional = new ChangeStimulusStateBuilder(this, id)
                .buildMultipleReceivedOneEmitted(propStimuli, emotional);
        ChangeStimulusState changeAdrenergic = new ChangeStimulusStateBuilder(this, id)
                .buildOneReceivedOneEmitted(null, adrenergic);

        BehaviouralEfficiencyState behaviouralState = new BehaviouralEfficiencyState();
        behaviouralState.setBehaviouralEfficiency(behaviouralEfficiency);
        behaviouralState.setNumberOfObjects(perceptions.size());
        behaviouralState.setComplexTask(perceptions.size() >= Constants.COMPLEX_TASK);
        behaviouralState.setChangeStimulusState(changeEmotional);

        persist(changeEmotional, changeAdrenergic, behaviouralState);
    }

    /**
     * Serotonergic satiety signal ∈ [0, 1]: the mean depth of the four active drives inside Mapa's
     * homeostatic equilibrium band {@code [MIN_AROUSAL_LEVEL, EQUILIBRIUM_BAND_UPPER]}. Low arousal
     * (well-regulated) → high satiety → tonic serotonin rises (patience/quieting). Unmet needs → 0.
     */
    private double computeSatiety() {
        double span = Constants.EQUILIBRIUM_BAND_UPPER - Constants.MIN_AROUSAL_LEVEL;
        String[] active = {Constants.HUNGER, Constants.SLEEP, Constants.PAIN, Constants.TEDIUM};
        double sum = 0.0;
        for (String drive : active) {
            double depth = (Constants.EQUILIBRIUM_BAND_UPPER - creature.emotions().getLevel(drive)) / span;
            sum += Math.max(0.0, Math.min(1.0, depth));
        }
        return sum / active.length;
    }

    // Mapa §4.1.4 / Diamond et al. (2006) Yerkes-Dodson curves, normalised to [0, 1]:
    //   Simple task  (0–1 objects): monotonic Eq 4.1 — 16(1−e^{−0.4A}) / 16  (asymptote = 16)
    //   Complex task (≥2 objects):  inverted-U Eq 4.2 — A·(280/49 − (40/49)·A) / 10  (peak = 10 at A*=3.5)
    // Both map into [MIN_STEP, MAX_STEP] via FullAppraisal.
    double normalizedBehaviouralEfficiency(double arousal, int perceptionsCount) {
        if (perceptionsCount < Constants.COMPLEX_TASK) {
            return (16 * (1 - Math.exp(-0.4 * arousal))) / 16.0;
        } else {
            return (arousal * (280.0/49 - (40.0/49) * arousal)) / 10.0;
        }
    }
}
