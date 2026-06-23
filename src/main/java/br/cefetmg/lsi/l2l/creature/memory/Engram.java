package br.cefetmg.lsi.l2l.creature.memory;

import br.cefetmg.lsi.l2l.common.SequentialId;
import br.cefetmg.lsi.l2l.creature.common.ActionType;
import br.cefetmg.lsi.l2l.creature.common.Perception;
import br.cefetmg.lsi.l2l.creature.components.Emotion;

import java.io.Serializable;

public record Engram(
        ActionType actionType,
        SequentialId id,
        Emotion emotionAtDecision,
        Perception perception,
        long layCycle,
        double emotionDelta,
        double eligibility,
        long reinforcedCycle
) implements Serializable {

    public Engram {
        if (emotionAtDecision != null) {
            Emotion copy = new Emotion(emotionAtDecision.getName());
            copy.setLevel(emotionAtDecision.getLevel());
            emotionAtDecision = copy;
        }
    }
}
