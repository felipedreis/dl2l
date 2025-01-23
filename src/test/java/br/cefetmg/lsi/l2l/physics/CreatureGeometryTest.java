package br.cefetmg.lsi.l2l.physics;

import br.cefetmg.lsi.l2l.common.Point;
import br.cefetmg.lsi.l2l.common.SequentialId;
import org.junit.jupiter.api.Test;
import org.newdawn.slick.geom.Rectangle;

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
}
