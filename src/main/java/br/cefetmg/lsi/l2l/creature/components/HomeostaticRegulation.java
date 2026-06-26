package br.cefetmg.lsi.l2l.creature.components;

import br.cefetmg.lsi.l2l.common.Constants;
import br.cefetmg.lsi.l2l.common.SequentialId;
import br.cefetmg.lsi.l2l.creature.bd.ChangeStimulusState;
import br.cefetmg.lsi.l2l.creature.bd.ChangeStimulusStateBuilder;
import br.cefetmg.lsi.l2l.creature.bd.EmotionalState;
import br.cefetmg.lsi.l2l.creature.bd.InternalDynamicState;
import br.cefetmg.lsi.l2l.creature.bd.RegulationBatchStat;
import br.cefetmg.lsi.l2l.creature.common.ActionType;
import br.cefetmg.lsi.l2l.creature.ml.WakeUp;
import br.cefetmg.lsi.l2l.creature.common.ActionType;
import br.cefetmg.lsi.l2l.stimuli.*;
import br.cefetmg.lsi.l2l.stimuli.AdenosinergicStimulus;
import br.cefetmg.lsi.l2l.world.Self;

import java.util.ArrayList;
import java.util.List;

/**
 * Created by felipe on 02/01/17.
 */
public class HomeostaticRegulation extends CreatureComponent {

    public HomeostaticRegulation(SequentialId id) {
        super(id);
    }

    @Override
    public void onReceive(Object message) {
        List stimuli = (List) message;

        int batchSize = stimuli.size();
        int regulatingCount = 0, hungerHits = 0, sleepHits = 0, drivesTouchedMask = 0;

        for (Object aStimuli : stimuli) {
            Stimulus stimulus = (Stimulus) aStimuli;

            EmotionalState before = new EmotionalState();
            before.setHunger(creature.emotions().getLevel(Constants.HUNGER));
            before.setSleep(creature.emotions().getLevel(Constants.SLEEP));

            Stimulus emitted = null;

            if (stimulus instanceof AdrenergicStimulus) {
                AdrenergicStimulus adrenergic = (AdrenergicStimulus) stimulus;
                creature.emotions().regulateAll(adrenergic.delta);
            } if (stimulus instanceof NutritiveStimulus) {
                NutritiveStimulus nutritive = (NutritiveStimulus) stimulus;
                Emotion regulated = creature.emotions().regulate(Constants.HUNGER, -nutritive.nutritiveValue);

                emitted = new EvaluationStimulus(stimulus.origin, nextStimulusId(),
                        stimulus.origin, nutritive.objectType, ActionType.EAT, regulated, -nutritive.nutritiveValue);

                creature.valuation().tell(emitted, self());
            } if (stimulus instanceof AdenosinergicStimulus) {
                AdenosinergicStimulus drive = (AdenosinergicStimulus) stimulus;
                creature.emotions().regulate(Constants.SLEEP, drive.delta);
                // No EvaluationStimulus: drive accumulation is not a reinforceable event.
            } if (stimulus instanceof CholinergicStimulus) {
                CholinergicStimulus cholinergic = (CholinergicStimulus) stimulus;
                Emotion regulated = creature.emotions().regulate(Constants.SLEEP, -cholinergic.delta);
                emitted = new EvaluationStimulus(stimulus.origin, nextStimulusId(), id, Self.get(), ActionType.SLEEP,
                    regulated, -cholinergic.delta);
                creature.valuation().tell(emitted, self());
                // Sleep drive satisfied → creature is waking; abort any ongoing consolidation.
                if (regulated.getLevel() <= 0) {
                    logger.info(String.format("HomeostaticRegulation[%s]: sleep drive exhausted, sending WakeUp", id));
                    creature.memoryConsolidator().tell(new WakeUp(), self());
                }
            } if (stimulus instanceof NociceptiveStimulus) {
                NociceptiveStimulus noci = (NociceptiveStimulus) stimulus;
                Emotion regulated = creature.emotions().regulate(Constants.PAIN, noci.painIntensity);
                // Only send EvaluationStimulus for deliberate actions (active EAT on cactus).
                // Passive collision pain drives avoidance through arousal alone.
                if (noci.action != null) {
                    emitted = new EvaluationStimulus(stimulus.origin, nextStimulusId(),
                            stimulus.origin, noci.objectType, noci.action, regulated, noci.painIntensity);
                    creature.valuation().tell(emitted, self());
                }
            } if (stimulus instanceof TediumStimulus) {
                TediumStimulus tedium = (TediumStimulus) stimulus;
                Emotion regulated = creature.emotions().regulate(Constants.TEDIUM, tedium.delta);
                // Reinforce WANDER (tedium falls) and penalise OBSERVE (tedium rises).
                if (tedium.action == ActionType.WANDER || tedium.action == ActionType.OBSERVE) {
                    emitted = new EvaluationStimulus(stimulus.origin, nextStimulusId(),
                            id, Self.get(), tedium.action, regulated, tedium.delta);
                    creature.valuation().tell(emitted, self());
                }
            }

            EmotionalState after = new EmotionalState();
            after.setHunger(creature.emotions().getLevel(Constants.HUNGER));
            after.setSleep(creature.emotions().getLevel(Constants.SLEEP));

            ChangeStimulusState change = new ChangeStimulusStateBuilder(this, this.id)
                    .buildOneReceivedOneEmitted(stimulus, emitted);
            InternalDynamicState dynamicState = new InternalDynamicState(before, after, change);

            persist(change, dynamicState);

            if (stimulus instanceof NutritiveStimulus)       { regulatingCount++; hungerHits++; drivesTouchedMask |= 1; }
            else if (stimulus instanceof CholinergicStimulus) { regulatingCount++; sleepHits++; drivesTouchedMask |= 2; }
            else if (stimulus instanceof AdrenergicStimulus)  { regulatingCount++; drivesTouchedMask |= 1 | 2; }
        }

        boolean sameDriveCollision = (hungerHits >= 2) || (sleepHits >= 2);
        ChangeStimulusState batchChange = new ChangeStimulusStateBuilder(this, this.id)
                .buildMultipleReceivedOneEmitted(new ArrayList<>(), null);
        persist(batchChange, new RegulationBatchStat(batchSize, regulatingCount,
                sameDriveCollision, drivesTouchedMask, batchChange));
    }
}
