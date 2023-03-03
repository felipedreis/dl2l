package br.cefetmg.lsi.l2l.physics;

import br.cefetmg.lsi.l2l.common.Point;
import br.cefetmg.lsi.l2l.common.SequentialId;

import java.io.Serializable;
import java.util.List;

public interface Collidable extends Serializable {
  Point getMostLeftDownPoint();

  Point getMostRightUpPoint();

  List<SequentialId[]> collidesWith(Collidable other);
}
