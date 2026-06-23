package br.cefetmg.lsi.l2l.creature.memory;

import br.cefetmg.lsi.l2l.common.SequentialId;
import br.cefetmg.lsi.l2l.creature.common.ActionType;
import br.cefetmg.lsi.l2l.creature.common.Perception;
import br.cefetmg.lsi.l2l.creature.components.Emotion;

import java.io.Serializable;

public record ShortTermMemory(
        ActionType actionType,
        SequentialId id,
        Emotion emotion,
        Perception perception,
        long cognitiveCycle
) implements Serializable {

    // Defensive copy of Emotion: it is mutable and shared with EmotionalSystemActor.
    // Without this copy, the level stored here would drift if the emotional system
    // mutates the same Emotion object after the STM is created.
    public ShortTermMemory {
        if (emotion != null) {
            Emotion copy = new Emotion(emotion.getName());
            copy.setLevel(emotion.getLevel());
            emotion = copy;
        }
    }
}
