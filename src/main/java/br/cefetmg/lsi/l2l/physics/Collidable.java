package br.cefetmg.lsi.l2l.physics;

import br.cefetmg.lsi.l2l.common.Point;

import java.io.Serializable;

public interface Collidable extends Serializable {
  Point getMostLeftDownPoint();

  Point getMostRightUpPoint();
}
