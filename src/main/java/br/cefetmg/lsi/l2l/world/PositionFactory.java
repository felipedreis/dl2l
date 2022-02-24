package br.cefetmg.lsi.l2l.world;

import br.cefetmg.lsi.l2l.common.Point;

/**
 * A position factory abstracts the way things are positioned around the world. Those positions must be generated into
 * world's bounds.
 *
 * Created by felipe on 27/03/17.
 */
public interface PositionFactory {

    Point nextPosition();
}
