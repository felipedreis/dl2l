package br.cefetmg.lsi.l2l.physics;

import br.cefetmg.lsi.l2l.common.SequentialId;

import java.util.*;

public class OrderingCollisionDetector extends CollisionDetector {

  private static Comparator<Collidable> xComparator = Comparator.comparingDouble(a -> a.getMostLeftDownPoint().x);
  private static Comparator<Collidable> yComparator = Comparator.comparingDouble(a -> a.getMostLeftDownPoint().y);

  List<Collidable> xOrderedObjects;

  public OrderingCollisionDetector() {
    xOrderedObjects = new ArrayList<>();
  }

  @Override
  public List<Collidable[]> pruneCollidingObjects() {

    xOrderedObjects.addAll(worldObjects.values());
    xOrderedObjects.addAll(creatures.values());

    xOrderedObjects.sort(xComparator);

    double firstObjectEndingX = xOrderedObjects.get(0).getMostRightUpPoint().x;
    int i = 0;

    List<Collidable[]> possibleXCollidingObjects = new ArrayList<>();

    for (int j = 1; j < xOrderedObjects.size(); ++j) {
      double objectBeginningX = xOrderedObjects.get(j).getMostLeftDownPoint().x;
      if (objectBeginningX < firstObjectEndingX)
        continue;

      Collidable[] colliding = xOrderedObjects.subList(i, j).toArray(Collidable[]::new);
      possibleXCollidingObjects.add(colliding);
    }
    return possibleXCollidingObjects;
  }
}
