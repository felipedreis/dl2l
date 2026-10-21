package br.cefetmg.lsi.l2l.stimuli;

import br.cefetmg.lsi.l2l.common.SequentialId;
import br.cefetmg.lsi.l2l.creature.common.Perception;
import br.cefetmg.lsi.l2l.creature.components.Emotion;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Created by felipe on 13/03/17.
 */
public class EmotionalStimulus extends Stimulus {

    public final List<Perception> perceptions;
    public final Emotion maxEmotion;
    public final double behaviouralEfficiency;
    /**
     * Snapshot of every active drive's emotion this cycle, by name (issue #99), so the focus
     * tracker can see all drives and not just the winner. Empty when the sender did not fill it.
     */
    public final Map<String, Emotion> drives;

    public EmotionalStimulus(SequentialId origin, SequentialId stimulusId, List<Perception> perceptions, Emotion maxEmotion, double behaviouralEfficiency) {
        this(origin, stimulusId, perceptions, maxEmotion, behaviouralEfficiency, Map.of());
    }

    public EmotionalStimulus(SequentialId origin, SequentialId stimulusId, List<Perception> perceptions, Emotion maxEmotion, double behaviouralEfficiency,
                             Map<String, Emotion> drives) {
        super(origin, stimulusId);
        this.drives = Collections.unmodifiableMap(drives);
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
