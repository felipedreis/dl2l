package br.cefetmg.lsi.l2l.creature.common;

import br.cefetmg.lsi.l2l.common.SequentialId;
import br.cefetmg.lsi.l2l.world.WorldObjectType;

/**
 * Created by felipe on 18/03/17.
 */
public class Action {

    public final ActionType type;
    public final Perception perception;

    public Action(ActionType type, Perception perception) {
        this.type = type;
        this.perception = perception;
    }


    public WorldObjectType getTarget() {
        return perception.objectType.getOrElse(null);
    }
    @Override
    public String toString() {
        return "Action{" +
                "type=" + type +
                ", perception=" + perception +
                '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Action)) return false;

        Action action = (Action) o;

        SequentialId objId = perception.id;

        if (type != action.type) return false;
        return objId != null ? objId.equals(action.perception.id) : action.perception.id == null;

    }

    @Override
    public int hashCode() {
        int result = type != null ? type.hashCode() : 0;
        result = 31 * result + (perception.id != null ? perception.id.hashCode() : 0);
        return result;
    }
}
