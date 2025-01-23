package br.cefetmg.lsi.l2l.common;

import br.cefetmg.lsi.l2l.physics.*;
import br.cefetmg.lsi.l2l.world.FruitType;
import br.cefetmg.lsi.l2l.world.PositionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.newdawn.slick.geom.Rectangle;

import java.util.List;
import java.util.Random;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.*;

public class QuadTreeTest {

    QuadTree quadTree;
    PositionFactory factory;

    @BeforeEach
    public void init() {
        Point worldBoundaries = new Point(600, 600);

        quadTree = new QuadTree(new Rectangle(0, 0, worldBoundaries.x, worldBoundaries.y));
    }

    @Test
    public void testInsert() {
        final FruitType type = FruitType.GRAY_APPLE;
        List<ObjectGeometry> positioningAttrs = LongStream.range(0, 10)
                .boxed()
                .map(SequentialId::new)
                .map(id -> new WorldObjectPositioningAttr(id, new Point(id.key * type.radius * 2 + 1, 50), type))
                .map(ObjectGeometry::new)
                .toList();

        positioningAttrs.forEach(quadTree::insert);


        for (ObjectGeometry objectGeometry : positioningAttrs) {
            Rectangle rectangle = objectGeometry.getBoundingBox();

            List<Geometry> collisions = quadTree.query(rectangle);
            assertEquals(1, collisions.size());
            assertTrue(collisions.contains(objectGeometry));
        }
    }

    @Test
    public void testQuery() {
        CreatureGeometry creatureGeometry = getCreatureGeometry();
        List<ObjectGeometry> possibleCollisions = getObjectGeometries();

        Random random = new Random();

        List<ObjectGeometry> positioningAttrs = LongStream.range(0, 100)
                .boxed()
                .map(SequentialId::new)
                .map(id -> new WorldObjectPositioningAttr(id, new Point(random.nextDouble(301, 600), random.nextDouble(301, 600)), FruitType.GRAY_APPLE))
                .map(ObjectGeometry::new)
                .toList();

        possibleCollisions.forEach(quadTree::insert);
        positioningAttrs.forEach(quadTree::insert);
        Rectangle boundingBox = creatureGeometry.getBoundingBox();
        List<Geometry> collisions = quadTree.query(boundingBox);

        assertTrue(collisions.containsAll(possibleCollisions));
        assertEquals(possibleCollisions.size(), collisions.size());
    }

    @Test
    public void testRemove() {
        List<ObjectGeometry> geometries = getObjectGeometries();
        geometries.forEach(quadTree::insert);

        quadTree.remove(geometries.get(0));

        assertEquals(geometries.size() - 1, quadTree.size());
        assertTrue(quadTree.query(geometries.get(0).getBoundingBox()).isEmpty());
    }

    private static List<ObjectGeometry> getObjectGeometries() {
        FruitType type = FruitType.RED_APPLE;

        List<ObjectGeometry> possibleCollisions = List.of(
                new ObjectGeometry(new WorldObjectPositioningAttr(new SequentialId(1000), new Point(150, 150), type)),
                new ObjectGeometry(new WorldObjectPositioningAttr(new SequentialId(1001), new Point(200, 300), type)),
                new ObjectGeometry(new WorldObjectPositioningAttr(new SequentialId(1002), new Point(100, 200), type)),
                new ObjectGeometry(new WorldObjectPositioningAttr(new SequentialId(1003), new Point(230, 300), type))
        );
        return possibleCollisions;
    }

    private static CreatureGeometry getCreatureGeometry() {
        Point point = new Point(200, 200);
        SequentialId sequentialId = new SequentialId();
        CreaturePositioningAttr creaturePositioningAttr = new CreaturePositioningAttr(sequentialId,
                sequentialId.next(),
                sequentialId.next(),
                sequentialId.next(),
                sequentialId.next(),
                point,
                70,
                40,
                50, false, false);

        CreatureGeometry creatureGeometry = new CreatureGeometry(creaturePositioningAttr);
        return creatureGeometry;
    }
}
