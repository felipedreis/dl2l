package br.cefetmg.lsi.l2l.creature.components;

import br.cefetmg.lsi.l2l.cluster.settings.LearningSettings;
import br.cefetmg.lsi.l2l.common.Constants;
import br.cefetmg.lsi.l2l.common.SequentialId;
import br.cefetmg.lsi.l2l.creature.bd.BehaviouralEfficiencyState;
import br.cefetmg.lsi.l2l.creature.bd.ChangeStimulusState;
import br.cefetmg.lsi.l2l.creature.bd.ChangeStimulusStateBuilder;
import br.cefetmg.lsi.l2l.creature.common.Perception;
import br.cefetmg.lsi.l2l.stimuli.AdrenergicStimulus;
import br.cefetmg.lsi.l2l.stimuli.AdenosinergicStimulus;
import br.cefetmg.lsi.l2l.stimuli.EmotionalStimulus;
import br.cefetmg.lsi.l2l.stimuli.EndocrineTick;
import br.cefetmg.lsi.l2l.stimuli.NeuromodulatorTick;
import br.cefetmg.lsi.l2l.stimuli.OrexinergicStimulus;
import br.cefetmg.lsi.l2l.stimuli.ProprioceptiveStimulus;
import br.cefetmg.lsi.l2l.stimuli.SerotonergicStimulus;
import br.cefetmg.lsi.l2l.stimuli.Stimulus;
import br.cefetmg.lsi.l2l.world.Self;

import java.util.ArrayList;
import java.util.List;

/**
 * Created by felipe on 02/01/17.
 */
public class PartialAppraisal extends CreatureComponent {

    private final LearningSettings learningSettings;
    private CircadianClock circadian;
    private EmotionalSystem emotionalSystem;
    // Accumulated circadian sleep-drive; flushed every HOMEO_BATCH_SIZE cycles (driveRate varies per phase).
    private double accumulatedSleepDrive = 0;
    private int    metabolicBatchCycle   = 0;

    public PartialAppraisal(SequentialId id, LearningSettings learningSettings) {
        super(id);
        this.learningSettings = learningSettings;
    }

    @Override
    public void preStart() throws Exception {
        super.preStart();
        circadian = learningSettings.isCircadianEnabled() ? new ActiveCircadianClock() : new DisabledCircadianClock();
        emotionalSystem = creature.emotions();
    }

    @Override
    public void onReceive(Object message) {
        @SuppressWarnings("unchecked")
        List<Stimulus> stimuli = message instanceof List<?> list
                ? (List<Stimulus>) list
                : List.of();

        checkDeath();
        AdrenergicStimulus adrenergic = tickMetabolicPacemaker();
        tickNeuromodulators();
        releaseOrexin();
        tickEndocrine();

        List<ProprioceptiveStimulus> propStimuli = new ArrayList<>();
        for (Stimulus stimulus : stimuli) {
            switch (stimulus) {
                case ProprioceptiveStimulus ps -> propStimuli.add(ps);
                default -> {}
            }
        }

        EmotionalStimulus emotional = buildEmotionalStimulus(propStimuli);
        creature.fullAppraisal().tell(emotional);
        persistCycle(emotional, propStimuli, adrenergic);
    }

    /**
     * Checks whether any basic drive has reached the lethal ceiling and kills the creature if so.
     *
     * <p>Death is a basic-drive deficit (starvation, terminal sleep deprivation, extreme pain).
     * Affects (tedium, stress) are never lethal — they have their own regulatory ceilings.
     * The dominant drive is still used to build the emotional stimulus so FullAppraisal can
     * record the final action before the creature is removed from the simulation.
     */
    private void checkDeath() {
        if (emotionalSystem.getMaxDriveArousal().getLevel() >= Constants.MAX_AROUSAL_LEVEL)
            creature.kill();
    }

    /**
     * Advances the circadian clock and, every {@code HOMEO_BATCH_SIZE} cognitive cycles,
     * flushes one batched {@link AdrenergicStimulus} (hunger drift) and one
     * {@link AdenosinergicStimulus} (sleep pressure) to HomeostaticRegulation.
     *
     * <p>Sending one stimulus per batch rather than per cycle reduces the message rate
     * from ≈134/s to ≈7/s, preventing the stale-backlog problem where thousands of
     * AdenosinergicStimuli built up ahead of CholinergicStimuli and drove sleep to MAX
     * before clearing could happen. Since {@code DELTA} is constant, the batched delta
     * ({@code DELTA × HOMEO_BATCH_SIZE}) is biologically identical to sending N individual ones.
     * Sleep-drive rate is accumulated because it varies with circadian phase.
     *
     * @return the {@link AdrenergicStimulus} sent this cycle, or {@code null} if the batch is not yet full
     */
    private AdrenergicStimulus tickMetabolicPacemaker() {
        circadian.tick();
        double sleepDriveRate = circadian.driveRate();
        if (sleepDriveRate > 0) accumulatedSleepDrive += sleepDriveRate;

        if (++metabolicBatchCycle >= Constants.HOMEO_BATCH_SIZE) {
            return flushMetabolicBatch();
        }
        return null;
    }

    /**
     * Emits the tonic serotonin signal and the per-cycle neuromodulator reuptake tick.
     *
     * <p>Serotonin encodes global homeostatic satiety: how well-regulated the creature is
     * across all four active drives. High tonic serotonin slows exploratory drive (patience),
     * while low serotonin signals unmet needs and increases urgency. The {@link NeuromodulatorTick}
     * drives passive reuptake (decay) in {@link NeuromodulatorSystem}, keeping tonics bounded
     * even without receptor stimulation.
     */
    private void tickNeuromodulators() {
        if (!learningSettings.isNeuromodulatorLoopActive()) return;
        creature.neuromodulators().tell(
                new SerotonergicStimulus(this.id, nextStimulusId(), computeSatiety()));
        creature.neuromodulators().tell(
                new NeuromodulatorTick(this.id, nextStimulusId(), circadian.phase()));
    }

    /**
     * Releases orexin in inverse proportion to current sleep pressure.
     *
     * <p>Orexin (hypocretin) is the primary wakefulness-promoting neuromodulator. Its tonic
     * level is high when the creature is well-rested (low sleep arousal) and falls as sleep
     * pressure builds. {@link br.cefetmg.lsi.l2l.creature.components.FullAppraisal} uses the
     * orexin tonic to gate SLEEP out of the action set: when orexin ≥
     * {@code OREXIN_SLEEP_GATE_THRESHOLD} the creature is too alert to sleep; the gate opens
     * only when sleep pressure exceeds ≈50% of MAX_AROUSAL.
     */
    private void releaseOrexin() {
        if (!learningSettings.isOrexinEnabled()) return;
        double sleepPressure = emotionalSystem.getLevel(Constants.SLEEP);
        double orexinRelease = Math.max(0.0, 1.0 - sleepPressure / Constants.MAX_AROUSAL_LEVEL);
        creature.neuromodulators().tell(
                new OrexinergicStimulus(this.id, nextStimulusId(), orexinRelease));
    }

    /**
     * Sends the per-cycle pacemaker tick to the HPA-axis (cortisol) subsystem.
     *
     * <p>Each {@link EndocrineTick} carries the current circadian phase so
     * {@link EndocrineSystem} can apply both passive adrenal clearance ({@code cortisol *= DECAY})
     * and circadian-modulated synthesis with saturating negative feedback in one step.
     * Separating the tick from the stressor stimulus ({@link br.cefetmg.lsi.l2l.stimuli.CortisolStimulus})
     * ensures cortisol decays even when no stressor is active, mirroring the biological
     * HPA axis's autonomous circadian rhythm.
     */
    private void tickEndocrine() {
        if (!learningSettings.isEndocrineEnabled()) return;
        creature.endocrine().tell(
                new EndocrineTick(this.id, nextStimulusId(), circadian.phase()));
    }

    /**
     * Converts the batch of proprioceptive stimuli into an {@link EmotionalStimulus} for
     * {@link FullAppraisal}.
     *
     * <p>Each {@link ProprioceptiveStimulus} carries the type, distance, and angle of one
     * visible object. When no objects are perceived the creature is treated as perceiving
     * itself at distance 0 (Mapa's "self-perception" fallback), which keeps the action-
     * selection pipeline alive with WANDER/SLEEP available even in an empty visual field.
     * Behavioural efficiency (Yerkes-Dodson) is computed here because it depends on both
     * the dominant arousal level and the perceptual load (number of objects in view).
     */
    private EmotionalStimulus buildEmotionalStimulus(List<ProprioceptiveStimulus> propStimuli) {
        List<Perception> perceptions = new ArrayList<>();
        for (ProprioceptiveStimulus ps : propStimuli) {
            perceptions.add(new Perception(ps.getObjectType(), ps.getTargetId(),
                    ps.getDistance(), ps.getAngle()));
        }

        if (perceptions.isEmpty()) {
            double visionFieldAngle = creature.getVisionFieldPosition();
            perceptions.add(new Perception(Self.get(), id.father(), 0, visionFieldAngle));
        }

        Emotion maxEmotion = emotionalSystem.getMaxArousal();
        double behaviouralEfficiency = normalizedBehaviouralEfficiency(maxEmotion.getLevel(), perceptions.size());

        if (metricsExt != null) {
            metricsExt.setGauge("dl2l_creature_arousal", id.toString(), maxEmotion.getLevel());
        }

        logger.fine(String.format("PartialAppraisal[%s]: arousal=%.3f perceptions=%d behaviouralEfficiency=%.3f",
                id, maxEmotion.getLevel(), perceptions.size(), behaviouralEfficiency));
        perceptions.forEach(p -> logger.fine(String.format("PartialAppraisal[%s]:   perception type=%s angle=%.3f dist=%.1f",
                id, p.objectType, p.angle, p.distance)));

        return new EmotionalStimulus(this.id, nextStimulusId(), perceptions, maxEmotion, behaviouralEfficiency);
    }

    /**
     * Persists the cognitive cycle's observability records to the database.
     *
     * <p>Three records are written: the emotional change (proprioception → emotional stimulus),
     * the behavioural efficiency snapshot, and — when a metabolic batch was flushed this cycle —
     * the adrenergic change record. The adrenergic record is conditional because batching means
     * most cycles produce no metabolic stimulus.
     */
    private void persistCycle(EmotionalStimulus emotional,
                               List<ProprioceptiveStimulus> propStimuli,
                               AdrenergicStimulus adrenergic) {
        List<Stimulus> rawStimuli = new ArrayList<>(propStimuli);
        ChangeStimulusState changeEmotional = new ChangeStimulusStateBuilder(this, id)
                .buildMultipleReceivedOneEmitted(rawStimuli, emotional);

        BehaviouralEfficiencyState behaviouralState = new BehaviouralEfficiencyState();
        behaviouralState.setBehaviouralEfficiency(emotional.behaviouralEfficiency);
        behaviouralState.setNumberOfObjects(emotional.getPerceptions().size());
        behaviouralState.setComplexTask(emotional.getPerceptions().size() >= Constants.COMPLEX_TASK);
        behaviouralState.setChangeStimulusState(changeEmotional);

        if (adrenergic != null) {
            ChangeStimulusState changeAdrenergic = new ChangeStimulusStateBuilder(this, id)
                    .buildOneReceivedOneEmitted(null, adrenergic);
            persist(changeEmotional, changeAdrenergic, behaviouralState);
        } else {
            persist(changeEmotional, behaviouralState);
        }
    }

    private AdrenergicStimulus flushMetabolicBatch() {
        metabolicBatchCycle = 0;
        AdrenergicStimulus adrenergic = new AdrenergicStimulus(this.id, nextStimulusId(),
                Constants.DELTA * Constants.HOMEO_BATCH_SIZE);
        creature.homeostatic().tell(adrenergic);
        if (accumulatedSleepDrive > 0) {
            creature.homeostatic().tell(
                    new AdenosinergicStimulus(this.id, nextStimulusId(), accumulatedSleepDrive));
            accumulatedSleepDrive = 0;
        }
        return adrenergic;
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
            double depth = (Constants.EQUILIBRIUM_BAND_UPPER - emotionalSystem.getLevel(drive)) / span;
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
