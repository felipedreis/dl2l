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
import br.cefetmg.lsi.l2l.world.Self;

import java.util.ArrayList;
import java.util.List;

/**
 * Created by felipe on 02/01/17.
 */
public class HomeostaticRegulation extends CreatureComponent {

    private final LearningSettings learningSettings;

    public HomeostaticRegulation(SequentialId id, LearningSettings learningSettings) {
        super(id);
        this.learningSettings = learningSettings;
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
        return new ExpectancyContext(drive, creature.emotions().getLevel(drive));
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
        s.setHunger(creature.emotions().getLevel(Constants.HUNGER));
        s.setSleep(creature.emotions().getLevel(Constants.SLEEP));
        s.setPain(creature.emotions().getLevel(Constants.PAIN));
        s.setTedium(creature.emotions().getLevel(Constants.TEDIUM));
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
        // Tedium is an affect (boredom), not a metabolic need, so it is excluded from the sympathetic
        // metabolic drift — it is regulated by the reward system (see NeuromodulatorSystem).
        if (learningSettings.isCircadianEnabled()) {
            // Circadian clock owns sleep pressure via AdenosinergicStimulus; exclude SLEEP here.
            creature.emotions().regulate(Constants.HUNGER, s.delta);
            creature.emotions().regulate(Constants.PAIN,   s.delta);
        } else {
            creature.emotions().regulate(Constants.HUNGER, s.delta);
            creature.emotions().regulate(Constants.SLEEP,  s.delta);
            creature.emotions().regulate(Constants.PAIN,   s.delta);
        }
        return null;
    }

    private Stimulus handleNutritive(NutritiveStimulus s) {
        ExpectancyContext ctx = contextFor(Constants.HUNGER);
        Emotion regulated = creature.emotions().regulate(Constants.HUNGER, -s.nutritiveValue);
        Stimulus emitted = new EvaluationStimulus(s.origin, nextStimulusId(),
                s.origin, s.objectType, ActionType.EAT, regulated, realizedDelta(ctx, regulated), ctx);
        creature.valuation().tell(emitted);
        return emitted;
    }

    private Stimulus handleAdenosinergic(AdenosinergicStimulus s) {
        creature.emotions().regulate(Constants.SLEEP, s.delta);
        return null;
    }

    private Stimulus handleCholinergic(CholinergicStimulus s) {
        ExpectancyContext ctx = contextFor(Constants.SLEEP);
        Emotion regulated = creature.emotions().regulate(Constants.SLEEP, -s.delta);
        Stimulus emitted = new EvaluationStimulus(s.origin, nextStimulusId(), id, Self.get(),
                ActionType.SLEEP, regulated, realizedDelta(ctx, regulated), ctx);
        creature.valuation().tell(emitted);
        if (regulated.getLevel() <= 0) {
            logger.info(String.format("HomeostaticRegulation[%s]: sleep drive exhausted, sending WakeUp", id));
            creature.memoryConsolidator().tell(new WakeUp());
        }
        return emitted;
    }

    private Stimulus handleNociceptive(NociceptiveStimulus s) {
        ExpectancyContext ctx = contextFor(Constants.PAIN);
        Emotion regulated = creature.emotions().regulate(Constants.PAIN, s.painIntensity);
        if (s.action == null) return null;
        Stimulus emitted = new EvaluationStimulus(s.origin, nextStimulusId(),
                s.origin, s.objectType, s.action, regulated, realizedDelta(ctx, regulated), ctx);
        creature.valuation().tell(emitted);
        return emitted;
    }

    private Stimulus handleAnalgesic(AnalgesicStimulus s) {
        ExpectancyContext ctx = contextFor(Constants.PAIN);
        double currentPain = creature.emotions().getLevel(Constants.PAIN);
        double effectiveDelta = Math.min(s.delta, Math.max(0, currentPain));
        Emotion regulated = creature.emotions().regulate(Constants.PAIN, -effectiveDelta);
        if (s.action == null) return null;
        Stimulus emitted = new EvaluationStimulus(s.origin, nextStimulusId(),
                s.origin, s.objectType, s.action, regulated, realizedDelta(ctx, regulated), ctx);
        creature.valuation().tell(emitted);
        return emitted;
    }

    private Stimulus handleTedium(TediumStimulus s) {
        ExpectancyContext ctx = contextFor(Constants.TEDIUM);
        Emotion regulated = creature.emotions().regulate(Constants.TEDIUM, s.delta);
        if (s.action != ActionType.WANDER && s.action != ActionType.OBSERVE) return null;
        Stimulus emitted = new EvaluationStimulus(s.origin, nextStimulusId(),
                id, Self.get(), s.action, regulated, realizedDelta(ctx, regulated), ctx);
        creature.valuation().tell(emitted);
        return emitted;
    }

    // When pain exceeds the immune threshold, queue an AnalgesicStimulus back to self so
    // it is processed in the next batch — avoiding double-reduction within the current one.
    private void triggerImmuneResponseIfNeeded() {
        double currentPain = creature.emotions().getLevel(Constants.PAIN);
        if (currentPain <= Constants.PAIN_IMMUNE_THRESHOLD) return;
        double immune = Math.min(Constants.PAIN_IMMUNE_RATE, currentPain - Constants.PAIN_IMMUNE_THRESHOLD);
        logger.fine(String.format("HomeostaticRegulation[%s]: queuing immune pain decay=%.4f", id, immune));
        self().tell(new AnalgesicStimulus(id, nextStimulusId(), immune, null, null));
    }
}
