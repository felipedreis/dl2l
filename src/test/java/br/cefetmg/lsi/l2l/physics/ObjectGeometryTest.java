package br.cefetmg.lsi.l2l.physics;

import br.cefetmg.lsi.l2l.common.Point;
import br.cefetmg.lsi.l2l.common.SequentialId;
import br.cefetmg.lsi.l2l.world.FruitType;
import org.junit.jupiter.api.Test;
import org.newdawn.slick.geom.Rectangle;

import static org.junit.jupiter.api.Assertions.*;

public class ObjectGeometryTest {

    @Test
    public void testBoundingBox() {
        WorldObjectPositioningAttr attr = new WorldObjectPositioningAttr(new SequentialId(), Point.O, FruitType.GRAY_APPLE);

        ObjectGeometry objectGeometry = new ObjectGeometry(attr);

        Rectangle rectangle = objectGeometry.getBoundingBox();
        assertEquals(-FruitType.GRAY_APPLE.radius, rectangle.getX());
        assertEquals(-FruitType.GRAY_APPLE.radius, rectangle.getY());
        assertEquals(FruitType.GRAY_APPLE.radius * 2, rectangle.getWidth());
        assertEquals(rectangle.getHeight(), rectangle.getWidth());
    }
}
