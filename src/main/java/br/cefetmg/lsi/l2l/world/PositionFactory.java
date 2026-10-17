package br.cefetmg.lsi.l2l.world;

import br.cefetmg.lsi.l2l.common.Point;
import br.cefetmg.lsi.l2l.common.SequentialId;

/**
 * A position factory abstracts the way things are positioned around the world. Those positions must be generated into
 * world's bounds.
 *
 * Created by felipe on 27/03/17.
 */
public interface PositionFactory {

    Point nextPosition();

    /** Position for the object or creature with this id; stateless factories override it. */
    default Point positionFor(SequentialId id) {
        return nextPosition();
    }
}
