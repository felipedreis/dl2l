package br.cefetmg.lsi.l2l.physics;

import br.cefetmg.lsi.l2l.common.Constants;
import br.cefetmg.lsi.l2l.common.Point;
import br.cefetmg.lsi.l2l.common.SequentialId;
import org.newdawn.slick.geom.Circle;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Created by felipe on 07/01/17.
 */
public class CreatureGeometry extends Geometry implements Serializable, Collidable {
    public final SequentialId id;

    public final Circle body;
    public final Circle olfactoryField;
    public final Arc mouth;
    public final Arc visionField;

    public final Point mostLeftDown;

    public final Point mostRightUp;

    public final CreaturePositioningAttr creaturePositioningAttr;

    public CreatureGeometry(CreaturePositioningAttr attr) {
        this.creaturePositioningAttr = attr;

        body = new Circle((float) attr.position.x, (float) attr.position.y,
                (float) attr.bodyRadius);

        olfactoryField = new Circle((float) attr.position.x,
                (float) attr.position.y, (float) attr.olfactoryFieldRadius);

        visionField = new Arc((float) attr.position.x, (float) attr.position.y,
                (float) Constants.DEFAULT_VISION_FIELD_RADIUS,
                (float) attr.visionFieldPosition,
                (float) attr.visionFieldOpening, 10);

        mouth = new Arc((float) attr.position.x, (float) attr.position.y,
                (float) Constants.DEFAULT_MOUTH_RADIUS,
                (float) attr.visionFieldPosition,
                (float) Constants.DEFAULT_MOUTH_OPENING, 10);
        this.id = attr.bodyId;

        Point[] points = calculateBoundingBox();
        this.mostLeftDown = points[0];
        this.mostRightUp = points[1];
    }

    public Point[] calculateBoundingBox() {
        List<Point> points = new ArrayList<>();
        Point position = creaturePositioningAttr.position;

        Point olfactoryUp, olfactoryDown, olfactoryLeft, olfactoryRight;
        olfactoryDown = new Point(position.x, position.y - creaturePositioningAttr.olfactoryFieldRadius);
        olfactoryUp =  new Point(position.x, position.y + creaturePositioningAttr.olfactoryFieldRadius);
        olfactoryLeft =  new Point(position.x - creaturePositioningAttr.olfactoryFieldRadius, position.y);
        olfactoryRight =  new Point(position.x + creaturePositioningAttr.olfactoryFieldRadius, position.y);

        points.add(olfactoryDown);
        points.add(olfactoryUp);
        points.add(olfactoryLeft);
        points.add(olfactoryRight);

        float[] visionFieldPoints = visionField.getPoints();

        for (int i = 0; i < visionFieldPoints.length - 1; i += 2) {
            points.add(new Point(visionFieldPoints[i], visionFieldPoints[i + 1]));
        }

        points.sort(Comparator.comparing(Point::getX).thenComparing(Point::getY));

        Point leftUp = points.get(0);
        Point rightDown = points.get(1);

        return new Point[] {
            points.get(0),
            points.get(points.size() - 1)
        };
    }

    @Override
    public Point getMostLeftDownPoint() {
        return mostLeftDown;
    }

    @Override
    public Point getMostRightUpPoint() {
        return mostRightUp;
    }

    @Override
    public List<SequentialId[]> collidesWith(Collidable other) {
        return null;
    }

    @Override
    public void load() {
        if(loaded) {
            return;
        }
        super.load();
        //bodyTexture = loader.loadImage("body");
        //mouthTexture = loader.loadImage("mouth");
        loaded = true;
    }
}
