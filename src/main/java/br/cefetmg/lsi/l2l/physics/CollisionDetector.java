package br.cefetmg.lsi.l2l.physics;

import br.cefetmg.lsi.l2l.common.SequentialId;

import java.util.List;
import java.util.Map;

public abstract class CollisionDetector {

  protected Map<SequentialId, CreaturePositioningAttr> creatures;

  protected Map<SequentialId, WorldObjectPositioningAttr> worldObjects;

  public void updateCreature(SequentialId sequentialId, CreaturePositioningAttr creature) {
    creatures.put(sequentialId, creature);
  }

  public void removeWorldObject(SequentialId sequentialId) {
    worldObjects.remove(sequentialId);
  }

  public void addWorldObject(SequentialId sequentialId, WorldObjectPositioningAttr worldObject) {
    worldObjects.put(sequentialId, worldObject);
  }

  public abstract List<SequentialId[]> getCollidingObjects();
}
