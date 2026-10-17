package br.cefetmg.lsi.l2l.physics;

import br.cefetmg.lsi.l2l.common.Point;
import br.cefetmg.lsi.l2l.common.SequentialId;
import org.junit.jupiter.api.Test;
import org.newdawn.slick.geom.Circle;
import org.newdawn.slick.geom.Rectangle;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

public class CreatureGeometryTest {

    CreatureGeometry creatureGeometry;
    CreaturePositioningAttr attr;

    SequentialId sequentialId = new SequentialId();

    @Test
    public void creatureIsLookingIntoFirstQuadrant() {
        attr = new CreaturePositioningAttr(sequentialId,
                sequentialId.next(),
                sequentialId.next(),
                sequentialId.next(),
                sequentialId.next(),
                Point.O,
                30,
                20,
                100,
                false, false);

        creatureGeometry = new CreatureGeometry(attr);

        Rectangle rectangle = creatureGeometry.getBoundingBox();

        assertEquals(-100, rectangle.getX());
        assertEquals(-100, rectangle.getY());
        assertEquals(250, rectangle.getWidth());
        assertEquals(250, rectangle.getHeight());
    }


    @Test
    public void creatureIsLookingIntoSecondQuadrant() {
        attr = new CreaturePositioningAttr(sequentialId,
                sequentialId.next(),
                sequentialId.next(),
                sequentialId.next(),
                sequentialId.next(),
                Point.O,
                100,
                20,
                100,
                false, false);

        creatureGeometry = new CreatureGeometry(attr);

        Rectangle rectangle = creatureGeometry.getBoundingBox();

        assertEquals(-150, rectangle.getX());
        assertEquals(-100, rectangle.getY());
        assertEquals(250, rectangle.getWidth());
        assertEquals(250, rectangle.getHeight());
    }

    @Test
    public void creatureIsLookingIntoThirdQuadrant() {
        attr = new CreaturePositioningAttr(sequentialId,
                sequentialId.next(),
                sequentialId.next(),
                sequentialId.next(),
                sequentialId.next(),
                Point.O,
                185,
                20,
                100,
                false, false);

        creatureGeometry = new CreatureGeometry(attr);

        Rectangle rectangle = creatureGeometry.getBoundingBox();

        assertEquals(-150, rectangle.getX());
        assertEquals(-150, rectangle.getY());
        assertEquals(250, rectangle.getWidth());
        assertEquals(250, rectangle.getHeight());

    }

    @Test
    public void creatureIsLookingIntoFourthQuadrant() {
        attr = new CreaturePositioningAttr(sequentialId,
                sequentialId.next(),
                sequentialId.next(),
                sequentialId.next(),
                sequentialId.next(),
                Point.O,
                300,
                20,
                100,
                false, false);

        creatureGeometry = new CreatureGeometry(attr);

        Rectangle rectangle = creatureGeometry.getBoundingBox();
        assertEquals(-100, rectangle.getX());
        assertEquals(-150, rectangle.getY());
        assertEquals(250, rectangle.getWidth());
        assertEquals(250, rectangle.getHeight());
    }

    @Test
    public void creatureIsLookingUp() {
        attr = new CreaturePositioningAttr(sequentialId,
                sequentialId.next(),
                sequentialId.next(),
                sequentialId.next(),
                sequentialId.next(),
                Point.O,
                70,
                40,
                50,
                false, false);

        creatureGeometry = new CreatureGeometry(attr);

        Rectangle rectangle = creatureGeometry.getBoundingBox();
        assertEquals(-150, rectangle.getX());
        assertEquals(-50, rectangle.getY());
        assertEquals(200, rectangle.getWidth());
        assertEquals(200, rectangle.getHeight());
    }

    /**
     * Issue #96: the creature steers by an absolute world bearing in radians (Point.angleAlpha
     * == atan2(dy, dx), see FullAppraisal.produceCortical) and that value is what reaches
     * CreaturePositioningAttr.visionFieldPosition. A fruit dead ahead of the heading, well
     * inside mouth radius, must be inside the mouth arc whatever the heading.
     */
    @ParameterizedTest
    @ValueSource(doubles = {0.0, 1.0, 1.5707963, 3.0, -1.0, -1.5707963, -3.0})
    public void mouthContainsFruitDeadAheadOfRadianBearing(double bearingRad) {
        Point creature = new Point(500, 500);
        double ahead = 12;
        Circle fruit = new Circle((float) (creature.x + ahead * Math.cos(bearingRad)),
                (float) (creature.y + ahead * Math.sin(bearingRad)), 8);

        CreatureGeometry geometry = new CreatureGeometry(new CreaturePositioningAttr(sequentialId,
                sequentialId.next(), sequentialId.next(), sequentialId.next(), sequentialId.next(),
                creature, Arc.startAngleFor(bearingRad, 50), 50, 65, false, false));

        assertTrue(geometry.mouth.intersects(fruit),
                "fruit 12 units ahead of bearing " + bearingRad + " rad is outside the mouth arc");
    }
}
