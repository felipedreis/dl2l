package br.cefetmg.lsi.l2l.creature.common;

import akka.japi.Option;
import br.cefetmg.lsi.l2l.common.SequentialId;
import br.cefetmg.lsi.l2l.world.WorldObjectType;

import java.io.Serializable;

/**
 * Created by felipe on 13/03/17.
 */
public class Perception implements Serializable{
    public final Option<WorldObjectType> objectType;
    public final SequentialId id;
    public final double distance;
    public final double angle;

    public Perception(WorldObjectType objectType, SequentialId id, double distance, double angle) {
        if (objectType != null)
            this.objectType = new Option.Some<>(objectType);
        else this.objectType = Option.none();

        this.id = id;

        this.distance = distance;
        this.angle = angle;
    }

    @Override
    public String toString() {
        return "Perception{" +
                "id=" + id +
                ", distance=" + distance +
                '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Perception)) return false;

        Perception that = (Perception) o;

        if (Double.compare(that.distance, distance) != 0) return false;
        if (Double.compare(that.angle, angle) != 0) return false;
        return id.equals(that.id);

    }

    @Override
    public int hashCode() {
        int result;
        long temp;
        result = id.hashCode();
        temp = Double.doubleToLongBits(distance);
        result = 31 * result + (int) (temp ^ (temp >>> 32));
        temp = Double.doubleToLongBits(angle);
        result = 31 * result + (int) (temp ^ (temp >>> 32));
        return result;
    }
}
