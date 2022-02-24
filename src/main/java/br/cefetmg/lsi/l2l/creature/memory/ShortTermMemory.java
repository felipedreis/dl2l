package br.cefetmg.lsi.l2l.creature.memory;

import br.cefetmg.lsi.l2l.common.SequentialId;
import br.cefetmg.lsi.l2l.creature.common.ActionType;
import br.cefetmg.lsi.l2l.creature.components.Emotion;
import br.cefetmg.lsi.l2l.world.WorldObjectType;

import java.io.Serializable;

/**
 * Created by felipe on 31/08/17.
 */
public class ShortTermMemory implements Serializable {
    public final ActionType actionType;
    public final SequentialId id;
    public final Emotion emotion;

    public ShortTermMemory(ActionType actionType, SequentialId id, Emotion emotion) {
        this.emotion = emotion;
        this.id = id;
        this.actionType = actionType;
    }


}
