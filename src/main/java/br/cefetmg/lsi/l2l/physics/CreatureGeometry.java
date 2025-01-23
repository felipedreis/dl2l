package br.cefetmg.lsi.l2l.physics;

import br.cefetmg.lsi.l2l.common.Constants;
import br.cefetmg.lsi.l2l.common.Point;
import br.cefetmg.lsi.l2l.common.ResourceLoader;
import br.cefetmg.lsi.l2l.common.SequentialId;
import org.newdawn.slick.geom.Circle;
import org.newdawn.slick.geom.Rectangle;

import java.io.Serializable;

/**
 * Created by felipe on 07/01/17.
 */
public class CreatureGeometry implements Serializable, Geometry {
    public final SequentialId id;

    public final Circle body;
    public final Circle olfactoryField;
    public final Arc mouth;
    public final Arc visionField;

    //public Image bodyTexture;
    //public Image mouthTexture;

    public CreatureGeometry(CreaturePositioningAttr attr) {
        body = new Circle( attr.position.x,  attr.position.y,
                 attr.bodyRadius);

        olfactoryField = new Circle( attr.position.x,
                 attr.position.y,  attr.olfactoryFieldRadius);

        visionField = new Arc( attr.position.x,  attr.position.y,
                 Constants.DEFAULT_VISION_FIELD_RADIUS,
                 attr.visionFieldPosition,
                 attr.visionFieldOpening, 10);

        mouth = new Arc( attr.position.x,  attr.position.y,
                 Constants.DEFAULT_MOUTH_RADIUS,
                 attr.visionFieldPosition,
                 Constants.DEFAULT_MOUTH_OPENING, 10);
        this.id = attr.bodyId;
    }

    @Override
    public double getX() {
        return body.getX();
    }

    @Override
    public double getY() {
        return body.getY();
    }

    @Override
    public Point getPoint() {
        return new Point(body.getX(), body.getY());
    }

    @Override
    public Rectangle getBoundingBox() {
        Rectangle olfactoryFieldBox = new Rectangle(body.getCenterX() - olfactoryField.radius, body.getCenterY() - olfactoryField.radius,
                                                        olfactoryField.radius * 2, olfactoryField.radius * 2);

        Rectangle visionFieldBox = new Rectangle(body.getCenterX() - visionField.radius, body.getCenterY() - visionField.radius,
                visionField.radius * 2, visionField.radius * 2);
        double angle = visionField.startAngle + visionField.arcOpening / 2.0;

        int direction =  (int) (angle/90) % 4;

        return switch (direction) {
            case 0 -> makeRectangle(new Point(olfactoryFieldBox.getX(), olfactoryFieldBox.getY()),
                        new Point(visionFieldBox.getX() + visionFieldBox.getWidth(), visionFieldBox.getY() + visionFieldBox.getHeight()));
            case 1 -> makeRectangle(new Point(visionFieldBox.getX(), visionFieldBox.getMaxY()),
                    new Point(olfactoryFieldBox.getMaxX(), olfactoryFieldBox.getY()));
            case 2 -> makeRectangle(new Point(visionFieldBox.getX(), visionFieldBox.getY()),
                    new Point(olfactoryFieldBox.getMaxX(), olfactoryFieldBox.getMaxY()));
            case 3 -> makeRectangle(new Point(olfactoryFieldBox.getX(), olfactoryFieldBox.getMaxY()),
                    new Point(visionFieldBox.getMaxX(), visionFieldBox.getY()));
            default -> null;
        };
    }

    private Rectangle makeRectangle(Point a, Point b) {
        double width = Math.abs(a.x - b.x);
        double height = Math.abs(a.y - b.y);
        if (a.y < b.y) {
            return new Rectangle(a.x, a.y, width, height);
        } else {
            return new Rectangle(a.x, b.y, width, height);
        }
    }
}
