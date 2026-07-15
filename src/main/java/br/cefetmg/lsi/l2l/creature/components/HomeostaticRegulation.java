package br.cefetmg.lsi.l2l.creature.components;

import br.cefetmg.lsi.l2l.cluster.settings.LearningSettings;
import br.cefetmg.lsi.l2l.common.Constants;
import br.cefetmg.lsi.l2l.common.SequentialId;
import br.cefetmg.lsi.l2l.creature.bd.ChangeStimulusState;
import br.cefetmg.lsi.l2l.creature.bd.ChangeStimulusStateBuilder;
import br.cefetmg.lsi.l2l.creature.bd.EmotionalState;
import br.cefetmg.lsi.l2l.creature.bd.InternalDynamicState;
import br.cefetmg.lsi.l2l.creature.bd.RegulationBatchStat;
import br.cefetmg.lsi.l2l.creature.common.ActionType;
import br.cefetmg.lsi.l2l.creature.conditioning.expectancy.ExpectancyContext;
import br.cefetmg.lsi.l2l.creature.ml.WakeUp;
import br.cefetmg.lsi.l2l.stimuli.*;
import br.cefetmg.lsi.l2l.stimuli.CortisolStimulus;
import br.cefetmg.lsi.l2l.world.Self;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Created by felipe on 02/01/17.
 */
public class HomeostaticRegulation extends CreatureComponent {

    private final LearningSettings learningSettings;
    // Consecutive above-threshold tick count per drive; reset to 0 when drive drops below threshold.
    private final Map<String, Integer> stressorStreak = new HashMap<>();
    // Cycle counters for rate-limiting drive-deprivation RPE events (one per N adrenergic ticks).
    private int hungerDeprivationCycle = 0;
    private int sleepDeprivationCycle  = 0;
    private EmotionalSystem emotionalSystem;

    public HomeostaticRegulation(SequentialId id, LearningSettings learningSettings) {
        super(id);
        this.learningSettings = learningSettings;
    }

    @Override
    public void preStart() throws Exception {
        super.preStart();
        emotionalSystem = creature.emotions();
    }

    @Override
    public void onReceive(Object message) {
        List stimuli = (List) message;

        int batchSize = stimuli.size();
        int regulatingCount = 0, hungerHits = 0, sleepHits = 0, drivesTouchedMask = 0;

        for (Object aStimuli : stimuli) {
            Stimulus stimulus = (Stimulus) aStimuli;

            EmotionalState before = emotionalSnapshot();
            Stimulus emitted = dispatch(stimulus);
            EmotionalState after = emotionalSnapshot();

            ChangeStimulusState change = new ChangeStimulusStateBuilder(this, this.id)
                    .buildOneReceivedOneEmitted(stimulus, emitted);
            persist(change, new InternalDynamicState(before, after, change));

            if (stimulus instanceof NutritiveStimulus)        { regulatingCount++; hungerHits++; drivesTouchedMask |= 1; }
            else if (stimulus instanceof CholinergicStimulus) { regulatingCount++; sleepHits++; drivesTouchedMask |= 2; }
            else if (stimulus instanceof AdrenergicStimulus)  { regulatingCount++; drivesTouchedMask |= 1 | 2; }
        }

        triggerImmuneResponseIfNeeded();

        boolean sameDriveCollision = (hungerHits >= 2) || (sleepHits >= 2);
        ChangeStimulusState batchChange = new ChangeStimulusStateBuilder(this, this.id)
                .buildMultipleReceivedOneEmitted(new ArrayList<>(), null);
        persist(batchChange, new RegulationBatchStat(batchSize, regulatingCount,
                sameDriveCollision, drivesTouchedMask, batchChange));
    }

    /** Capture the regulated drive's pre-interaction level as the expectancy-predictor context. */
    private ExpectancyContext contextFor(String drive) {
        return new ExpectancyContext(drive, emotionalSystem.getLevel(drive));
    }

    /**
     * The <em>realized</em> arousal change of a regulation event: post-level minus the pre-level
     * captured in {@code ctx}. This is the true outcome the creature experienced — unlike the
     * <em>intended</em> delta, it is 0 when the drive was already at its floor/ceiling and was
     * clamped. Rewarding the realized change (not the intended one) is what stops a creature from
     * being reinforced for e.g. sleeping when it is not sleepy, and makes consummatory reward
     * genuinely depend on the drive level (more hunger relieved when starving than when sated).
     */
    private static double realizedDelta(ExpectancyContext ctx, Emotion regulated) {
        return regulated.getLevel() - ctx.dominantDriveLevel();
    }

    private EmotionalState emotionalSnapshot() {
        EmotionalState s = new EmotionalState();
        s.setHunger(emotionalSystem.getLevel(Constants.HUNGER));
        s.setSleep(emotionalSystem.getLevel(Constants.SLEEP));
        s.setPain(emotionalSystem.getLevel(Constants.PAIN));
        s.setTedium(emotionalSystem.getLevel(Constants.TEDIUM));
        return s;
    }

    private Stimulus dispatch(Stimulus stimulus) {
        if (stimulus instanceof AdrenergicStimulus)    return handleAdrenergic((AdrenergicStimulus) stimulus);
        if (stimulus instanceof NutritiveStimulus)     return handleNutritive((NutritiveStimulus) stimulus);
        if (stimulus instanceof AdenosinergicStimulus) return handleAdenosinergic((AdenosinergicStimulus) stimulus);
        if (stimulus instanceof CholinergicStimulus)   return handleCholinergic((CholinergicStimulus) stimulus);
        if (stimulus instanceof NociceptiveStimulus)   return handleNociceptive((NociceptiveStimulus) stimulus);
        if (stimulus instanceof AnalgesicStimulus)     return handleAnalgesic((AnalgesicStimulus) stimulus);
        if (stimulus instanceof TediumStimulus)        return handleTedium((TediumStimulus) stimulus);
        return null;
    }

    private Stimulus handleAdrenergic(AdrenergicStimulus s) {
        // Sympathetic metabolic drift applies to basic drives only. The affects are driven by their
        // own pathways, not a metabolic clock: pain by nociception (injury), tedium by the reward
        // system (boredom = reward absence). So neither is raised here.
        Emotion hungerAfter = emotionalSystem.regulate(Constants.HUNGER, s.delta);
        emitCortisolIfStressed(Constants.HUNGER, hungerAfter.getLevel());
        emitDeprivationRpe(Constants.HUNGER, hungerAfter, s.delta, hungerDeprivationCycle++);
        if (!learningSettings.isCircadianEnabled()) {
            // Circadian clock owns sleep pressure via AdenosinergicStimulus when enabled; otherwise
            // sleep drifts metabolically here.
            Emotion sleepAfter = emotionalSystem.regulate(Constants.SLEEP, s.delta);
            emitCortisolIfStressed(Constants.SLEEP, sleepAfter.getLevel());
        }
        return null;
    }

    private Stimulus handleNutritive(NutritiveStimulus s) {
        ExpectancyContext ctx = contextFor(Constants.HUNGER);
        Emotion regulated = emotionalSystem.regulate(Constants.HUNGER, -s.nutritiveValue);
        Stimulus emitted = new EvaluationStimulus(s.origin, nextStimulusId(),
                s.origin, s.objectType, ActionType.EAT, regulated, realizedDelta(ctx, regulated), ctx);
        creature.valuation().tell(emitted);
        return emitted;
    }

    private Stimulus handleAdenosinergic(AdenosinergicStimulus s) {
        Emotion sleepAfter = emotionalSystem.regulate(Constants.SLEEP, s.delta);
        emitCortisolIfStressed(Constants.SLEEP, sleepAfter.getLevel());
        emitDeprivationRpe(Constants.SLEEP, sleepAfter, s.delta, sleepDeprivationCycle++);
        return null;
    }

    private Stimulus handleCholinergic(CholinergicStimulus s) {
        ExpectancyContext ctx = contextFor(Constants.SLEEP);
        Emotion regulated = emotionalSystem.regulate(Constants.SLEEP, -s.delta);
        Stimulus emitted = new EvaluationStimulus(s.origin, nextStimulusId(), id, Self.get(),
                ActionType.SLEEP, regulated, realizedDelta(ctx, regulated), ctx);
        creature.valuation().tell(emitted);
        if (regulated.getLevel() <= 0) {
            logger.fine(String.format("HomeostaticRegulation[%s]: sleep drive exhausted, sending WakeUp", id));
            creature.memoryConsolidator().tell(new WakeUp());
        }
        return emitted;
    }

    private Stimulus handleNociceptive(NociceptiveStimulus s) {
        ExpectancyContext ctx = contextFor(Constants.PAIN);
        Emotion regulated = emotionalSystem.regulate(Constants.PAIN, s.painIntensity);
        emitCortisolIfStressed(Constants.PAIN, regulated.getLevel());
        if (s.action == null) return null;
        Stimulus emitted = new EvaluationStimulus(s.origin, nextStimulusId(),
                s.origin, s.objectType, s.action, regulated, realizedDelta(ctx, regulated), ctx);
        creature.valuation().tell(emitted);
        return emitted;
    }

    private Stimulus handleAnalgesic(AnalgesicStimulus s) {
        ExpectancyContext ctx = contextFor(Constants.PAIN);
        double currentPain = emotionalSystem.getLevel(Constants.PAIN);
        double effectiveDelta = Math.min(s.delta, Math.max(0, currentPain));
        Emotion regulated = emotionalSystem.regulate(Constants.PAIN, -effectiveDelta);
        if (s.action == null) return null;
        Stimulus emitted = new EvaluationStimulus(s.origin, nextStimulusId(),
                s.origin, s.objectType, s.action, regulated, realizedDelta(ctx, regulated), ctx);
        creature.valuation().tell(emitted);
        return emitted;
    }

    private Stimulus handleTedium(TediumStimulus s) {
        ExpectancyContext ctx = contextFor(Constants.TEDIUM);
        Emotion regulated = emotionalSystem.regulate(Constants.TEDIUM, s.delta);
        if (s.action != ActionType.WANDER && s.action != ActionType.OBSERVE) return null;
        Stimulus emitted = new EvaluationStimulus(s.origin, nextStimulusId(),
                id, Self.get(), s.action, regulated, realizedDelta(ctx, regulated), ctx);
        creature.valuation().tell(emitted);
        return emitted;
    }

    /**
     * Drive-deprivation negative RPE: when a drive rises above the homeostatic equilibrium band
     * without being relieved, that is a worse-than-expected outcome. Emit an EvaluationStimulus
     * to Valuation so the expectancy path computes a negative RPE and suppresses DA.
     * Rate-limited to one event per DEPRIVATION_RPE_INTERVAL ticks to avoid flooding Valuation.
     */
    private void emitDeprivationRpe(String driveName, Emotion regulated, double delta, int cycle) {
        if (!learningSettings.isExpectancyEnabled()) return;
        if (regulated.getLevel() <= Constants.EQUILIBRIUM_BAND_UPPER) return;
        if (cycle % Constants.DEPRIVATION_RPE_INTERVAL != 0) return;

        ExpectancyContext ctx = new ExpectancyContext(driveName, regulated.getLevel());
        // arousalVariation = +delta (drive went up); reward = -delta (negative).
        // Valuation computes rpe = reward − expected ≤ 0 → DA dips.
        Stimulus eval = new EvaluationStimulus(id, nextStimulusId(),
                id, Self.get(), ActionType.WANDER, regulated, delta, ctx);
        creature.valuation().tell(eval);
    }

    private void emitCortisolIfStressed(String driveName, double arousalLevel) {
        if (!learningSettings.isEndocrineEnabled()) return;
        double excess = arousalLevel - Constants.STRESS_ACTIVATION_THRESHOLD;
        if (excess > 0) {
            int streak = stressorStreak.merge(driveName, 1, Integer::sum);
            if (streak >= Constants.CORTISOL_STRESSOR_SUSTAIN_TICKS) {
                creature.endocrine().tell(
                        new CortisolStimulus(id, nextStimulusId(), excess * Constants.CORTISOL_STRESSOR_GAIN));
            }
        } else {
            stressorStreak.put(driveName, 0);
        }
    }

    // When pain exceeds the immune threshold, queue an AnalgesicStimulus back to self so
    // it is processed in the next batch — avoiding double-reduction within the current one.
    private void triggerImmuneResponseIfNeeded() {
        double currentPain = emotionalSystem.getLevel(Constants.PAIN);
        if (currentPain <= Constants.PAIN_IMMUNE_THRESHOLD) return;
        double immune = Math.min(Constants.PAIN_IMMUNE_RATE, currentPain - Constants.PAIN_IMMUNE_THRESHOLD);
        logger.fine(String.format("HomeostaticRegulation[%s]: queuing immune pain decay=%.4f", id, immune));
        self().tell(new AnalgesicStimulus(id, nextStimulusId(), immune, null, null));
    }
}
