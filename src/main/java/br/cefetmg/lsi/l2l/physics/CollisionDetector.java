package br.cefetmg.lsi.l2l.physics;

import br.cefetmg.lsi.l2l.common.SequentialId;

import java.util.*;
import java.util.stream.Collectors;

public abstract class CollisionDetector {

  protected Map<SequentialId, CreatureGeometry> creatures;

  protected Map<SequentialId, ObjectGeometry> worldObjects;

  public CollisionDetector(){
    creatures = new HashMap<>();
    worldObjects = new HashMap<>();
  }

  public void updateCreature(SequentialId sequentialId, CreatureGeometry creature) {
    creatures.put(sequentialId, creature);
  }

  public void removeWorldObject(SequentialId sequentialId) {
    worldObjects.remove(sequentialId);
  }

  public void addWorldObject(SequentialId sequentialId, ObjectGeometry worldObject) {
    worldObjects.put(sequentialId, worldObject);
  }

  public List<SequentialId[]> getCollidingObjects() {
      List<Collidable[]> possibleCollidingObjects = pruneCollidingObjects();

      return possibleCollidingObjects.parallelStream().map(this::checkCollidingObjects)
          .flatMap(Collection::stream)
          .collect(Collectors.toList());
  }

  List<SequentialId[]> checkCollidingObjects(Collidable[] collidables) {
    List<SequentialId[]> collidingIds = new ArrayList<>();
    for (int i = 0; i < collidables.length; ++i)
        for (int j = i + 1; j < collidables.length; ++j)
            collidingIds.addAll(collidables[i].collidesWith(collidables[j]));

    return collidingIds;
  }


  public abstract List<Collidable[]> pruneCollidingObjects();
}
