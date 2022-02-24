package br.cefetmg.lsi.l2l.creature.components;

import br.cefetmg.lsi.l2l.common.SequentialId;
import br.cefetmg.lsi.l2l.creature.bd.ChangeStimulusState;
import br.cefetmg.lsi.l2l.creature.bd.ChangeStimulusStateBuilder;
import br.cefetmg.lsi.l2l.creature.conditioning.OperantConditioning;
import br.cefetmg.lsi.l2l.creature.memory.MemorySystem;
import br.cefetmg.lsi.l2l.creature.memory.ShortTermMemory;
import br.cefetmg.lsi.l2l.stimuli.EvaluationStimulus;
import br.cefetmg.lsi.l2l.stimuli.Stimulus;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Created by felipe on 02/01/17.
 */
public class Valuation extends CreatureComponent {

    private OperantConditioning operantConditioning;

    private MemorySystem memorySystem;

    public Valuation(SequentialId id) {
        super(id);
    }

    @Override
    public void preStart() throws Exception {
        super.preStart();
        operantConditioning = creature.operantConditioning();
        memorySystem = creature.memory();
    }

    @Override
    public void onReceive(Object message) {
        List<Stimulus> stimuli = (List) message;

        for(Stimulus aStimuli : stimuli) {

            if(aStimuli instanceof EvaluationStimulus) {

                EvaluationStimulus evaluation = (EvaluationStimulus) aStimuli;

                List<ShortTermMemory> memories = memorySystem.getMemories(evaluation.objectId);
                ShortTermMemory mem = new ShortTermMemory(evaluation.executedAction, evaluation.objectId,
                        evaluation.regulatedEmotion);

                List correspondingMemories = memories.stream()
                        .filter(mem::equals)
                        .collect(Collectors.toList());

                boolean valence = evaluation.arousalVariation < 0;

                operantConditioning.varyProbability(evaluation.type, evaluation.executedAction, 1, valence);

                ChangeStimulusState change = new ChangeStimulusStateBuilder(this, this.id)
                        .buildOneReceivedOneEmitted(evaluation, null);

                persist(change);
            }

        }
    }
}
