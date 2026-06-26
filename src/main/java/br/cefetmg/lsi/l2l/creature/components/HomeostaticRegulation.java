package br.cefetmg.lsi.l2l.creature.components;

import br.cefetmg.lsi.l2l.cluster.SimulationSettingsExtension;
import br.cefetmg.lsi.l2l.common.Constants;
import br.cefetmg.lsi.l2l.common.SequentialId;
import br.cefetmg.lsi.l2l.creature.bd.ChangeStimulusState;
import br.cefetmg.lsi.l2l.creature.bd.ChangeStimulusStateBuilder;
import br.cefetmg.lsi.l2l.creature.bd.EmotionalState;
import br.cefetmg.lsi.l2l.creature.bd.InternalDynamicState;
import br.cefetmg.lsi.l2l.creature.bd.RegulationBatchStat;
import br.cefetmg.lsi.l2l.creature.common.ActionType;
import br.cefetmg.lsi.l2l.creature.ml.WakeUp;
import br.cefetmg.lsi.l2l.stimuli.*;
import br.cefetmg.lsi.l2l.world.Self;

import java.util.ArrayList;
import java.util.List;

/**
 * Created by felipe on 02/01/17.
 */
public class HomeostaticRegulation extends CreatureComponent {

    private boolean consolidationEnabled;

    public HomeostaticRegulation(SequentialId id) {
        super(id);
    }

    @Override
    public void preStart() throws Exception {
        super.preStart();
        consolidationEnabled = SimulationSettingsExtension.of(context().system())
                .learningSettings().isConsolidationEnabled();
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

    private EmotionalState emotionalSnapshot() {
        EmotionalState s = new EmotionalState();
        s.setHunger(creature.emotions().getLevel(Constants.HUNGER));
        s.setSleep(creature.emotions().getLevel(Constants.SLEEP));
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
        creature.emotions().regulateAll(s.delta);
        return null;
    }

    private Stimulus handleNutritive(NutritiveStimulus s) {
        Emotion regulated = creature.emotions().regulate(Constants.HUNGER, -s.nutritiveValue);
        Stimulus emitted = new EvaluationStimulus(s.origin, nextStimulusId(),
                s.origin, s.objectType, ActionType.EAT, regulated, -s.nutritiveValue);
        creature.valuation().tell(emitted, self());
        return emitted;
    }

    private Stimulus handleAdenosinergic(AdenosinergicStimulus s) {
        creature.emotions().regulate(Constants.SLEEP, s.delta);
        return null;
    }

    private Stimulus handleCholinergic(CholinergicStimulus s) {
        Emotion regulated = creature.emotions().regulate(Constants.SLEEP, -s.delta);
        Stimulus emitted = new EvaluationStimulus(s.origin, nextStimulusId(), id, Self.get(),
                ActionType.SLEEP, regulated, -s.delta);
        creature.valuation().tell(emitted, self());
        if (regulated.getLevel() <= 0 && consolidationEnabled) {
            logger.info(String.format("HomeostaticRegulation[%s]: sleep drive exhausted, sending WakeUp", id));
            creature.memoryConsolidator().tell(new WakeUp(), self());
        }
        return emitted;
    }

    private Stimulus handleNociceptive(NociceptiveStimulus s) {
        Emotion regulated = creature.emotions().regulate(Constants.PAIN, s.painIntensity);
        if (s.action == null) return null;
        Stimulus emitted = new EvaluationStimulus(s.origin, nextStimulusId(),
                s.origin, s.objectType, s.action, regulated, s.painIntensity);
        creature.valuation().tell(emitted, self());
        return emitted;
    }

    private Stimulus handleAnalgesic(AnalgesicStimulus s) {
        double currentPain = creature.emotions().getLevel(Constants.PAIN);
        double effectiveDelta = Math.min(s.delta, Math.max(0, currentPain));
        Emotion regulated = creature.emotions().regulate(Constants.PAIN, -effectiveDelta);
        if (s.action == null) return null;
        Stimulus emitted = new EvaluationStimulus(s.origin, nextStimulusId(),
                s.origin, s.objectType, s.action, regulated, -effectiveDelta);
        creature.valuation().tell(emitted, self());
        return emitted;
    }

    private Stimulus handleTedium(TediumStimulus s) {
        Emotion regulated = creature.emotions().regulate(Constants.TEDIUM, s.delta);
        if (s.action != ActionType.WANDER && s.action != ActionType.OBSERVE) return null;
        Stimulus emitted = new EvaluationStimulus(s.origin, nextStimulusId(),
                id, Self.get(), s.action, regulated, s.delta);
        creature.valuation().tell(emitted, self());
        return emitted;
    }

    // When pain exceeds the immune threshold, queue an AnalgesicStimulus back to self so
    // it is processed in the next batch — avoiding double-reduction within the current one.
    private void triggerImmuneResponseIfNeeded() {
        double currentPain = creature.emotions().getLevel(Constants.PAIN);
        if (currentPain <= Constants.PAIN_IMMUNE_THRESHOLD) return;
        double immune = Math.min(Constants.PAIN_IMMUNE_RATE, currentPain - Constants.PAIN_IMMUNE_THRESHOLD);
        logger.fine(String.format("HomeostaticRegulation[%s]: queuing immune pain decay=%.4f", id, immune));
        self().tell(new AnalgesicStimulus(id, nextStimulusId(), immune, null, null), self());
    }
}
