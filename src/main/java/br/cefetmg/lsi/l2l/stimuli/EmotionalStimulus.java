package br.cefetmg.lsi.l2l.stimuli;

import br.cefetmg.lsi.l2l.common.SequentialId;
import br.cefetmg.lsi.l2l.creature.common.Perception;
import br.cefetmg.lsi.l2l.creature.components.Emotion;

import java.util.List;

/**
 * Created by felipe on 13/03/17.
 */
public class EmotionalStimulus extends Stimulus {

    public final List<Perception> perceptions;
    public final Emotion maxEmotion;
    public final double behaviouralEfficiency;

    public EmotionalStimulus(SequentialId origin, SequentialId stimulusId, List<Perception> perceptions, Emotion maxEmotion, double behaviouralEfficiency) {
        super(origin, stimulusId);
        this.perceptions = perceptions;
        this.maxEmotion = maxEmotion;
        this.behaviouralEfficiency = behaviouralEfficiency;
    }

    public List<Perception> getPerceptions() {
        return perceptions;
    }

    public Emotion getMaxEmotion() {
        return maxEmotion;
    }

}
