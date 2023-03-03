package br.cefetmg.lsi.l2l;

import br.cefetmg.lsi.l2l.common.Constants;
import br.cefetmg.lsi.l2l.common.Point;
import br.cefetmg.lsi.l2l.common.SequentialId;
import br.cefetmg.lsi.l2l.physics.*;
import br.cefetmg.lsi.l2l.world.FruitType;
import org.junit.Before;
import org.junit.Test;

import javax.swing.*;
import java.awt.*;
import java.awt.geom.Arc2D;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;


public class CollisionDetectorTest {

  CollisionDetector collisionDetector;

  SequentialId creatureId;

  CreaturePositioningAttr creaturePositioningAttr;

  List<WorldObjectPositioningAttr> intersectingObjects;

  List<WorldObjectPositioningAttr> notIntersectingObjects;

  @Before
  public void setup(){
    collisionDetector = new OrderingCollisionDetector();
    creatureId = new SequentialId();
    creaturePositioningAttr = new CreaturePositioningAttr(
        creatureId,
        creatureId.next(),
        creatureId.next(),
        creatureId.next(),
        creatureId.next(),
        new Point(50., 50.),
        0.,
        120.,
        Constants.MIN_OLFACTORY_FIELD_RADIUS,
        false,
        false
    );

    intersectingObjects = new ArrayList<>();

    // generating intersecting objects
    for (int i = 0; i < 10; ++i) {
      double randomRadius = 10 + Math.random() * 30; // something between [10, 40]
      Point point = new Point(50. + randomRadius, 50. + randomRadius);

      intersectingObjects.add(new WorldObjectPositioningAttr(new SequentialId(), point, FruitType.RED_APPLE));
    }
  }

  @Test
  public void testCreatureGetBoundingBox() {
    CreatureGeometry geometry = new CreatureGeometry(creaturePositioningAttr);
    Point [] boundingBox = geometry.calculateBoundingBox();

    assertTrue(boundingBox[0].x < 0);
    assertTrue(boundingBox[0].y == 50);
  }

  @Test
  public void testCollidingObjects() {
    for (WorldObjectPositioningAttr attr : intersectingObjects)
      collisionDetector.addWorldObject(attr.id, new ObjectGeometry(attr));


    collisionDetector.updateCreature(creatureId, new CreatureGeometry(creaturePositioningAttr));

    List<SequentialId[]> collidingIds = collisionDetector.getCollidingObjects();

    System.out.println(collidingIds);
  }

  public void drawScene(Graphics g) {

    CreatureGeometry geometry = new CreatureGeometry(creaturePositioningAttr);

    g.drawOval((int) creaturePositioningAttr.position.x, (int) creaturePositioningAttr.position.y,
        (int) Constants.DEFAULT_BODY_RADIUS, (int) Constants.DEFAULT_BODY_RADIUS);

    g.drawArc((int) creaturePositioningAttr.position.x, (int) creaturePositioningAttr.position.y,
        (int) Constants.DEFAULT_VISION_FIELD_RADIUS, 10, (int) creaturePositioningAttr.visionFieldPosition, (int) creaturePositioningAttr.visionFieldOpening);
  }

  public static void main(String [] args) {

    CollisionDetectorTest test = new CollisionDetectorTest();
    test.setup();

    JFrame jFrame = new JFrame();
    JPanel canvas = new JPanel() {
      @Override
      protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        test.drawScene(g);
      }
    };

    jFrame.add(canvas);
    jFrame.setSize(500, 500);

    jFrame.setVisible(true);
  }
}
