package br.cefetmg.lsi.l2l.physics;

import br.cefetmg.lsi.l2l.common.Point;
import br.cefetmg.lsi.l2l.common.ResourceLoader;
import br.cefetmg.lsi.l2l.common.SequentialId;
import br.cefetmg.lsi.l2l.world.FruitType;
import br.cefetmg.lsi.l2l.world.PlantType;
import br.cefetmg.lsi.l2l.world.WorldObjectType;
//import org.newdawn.slick.Image;
import org.newdawn.slick.geom.Circle;
import org.newdawn.slick.geom.Polygon;
import org.newdawn.slick.geom.Rectangle;
import org.newdawn.slick.geom.Shape;

import java.util.Objects;

/**
 * Created by felipe on 07/01/17.
 */
public class ObjectGeometry implements Geometry {
    public final SequentialId id;

    public final Shape shape;
    public final WorldObjectType type;
    //private Image texture;
    public final Point point;

    public ObjectGeometry(WorldObjectPositioningAttr attr) {
        super();

        this.point = attr.position;
        this.type = attr.type;

        if (type instanceof FruitType fruitType) {
            this.shape = new Circle((float) point.x, (float) point.y, (float) fruitType.radius);
        } else if (type instanceof PlantType plantType) {
            this.shape = plantTriangle(point.x, point.y, plantType.radius, plantType == PlantType.CACTUS);
        } else {
            //texture = null;
            shape = null;
        }

        id = attr.id;
    }

    @Override
    public double getX() {
        return point.x;
    }

    @Override
    public double getY() {
        return point.y;
    }

    @Override
    public Point getPoint() {
        return point;
    }

    @Override
    public Rectangle getBoundingBox() {
        if (type instanceof FruitType fruitType)
            return new Rectangle(point.x - fruitType.radius, point.y - fruitType.radius, fruitType.radius*2, fruitType.radius*2);
        else if (type instanceof PlantType plantType)
            return new Rectangle(point.x - plantType.radius, point.y - plantType.radius, plantType.radius*2, plantType.radius*2);
        else return null;
    }

    // Equilateral triangle inscribed in a circle of the given radius.
    // pointingUp=true → CACTUS (▲), pointingUp=false → ALOE (▽).
    private static Polygon plantTriangle(double cx, double cy, double r, boolean pointingUp) {
        double baseAngle = pointingUp ? -Math.PI / 2 : Math.PI / 2;
        double[] pts = new double[6];
        for (int i = 0; i < 3; i++) {
            double a = baseAngle + i * 2 * Math.PI / 3;
            pts[i * 2]     = cx + r * Math.cos(a);
            pts[i * 2 + 1] = cy + r * Math.sin(a);
        }
        return new Polygon(pts);
    }

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) return false;
        ObjectGeometry that = (ObjectGeometry) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }

    @Override
    public String toString() {
        return "ObjectGeometry{" +
                "id=" + id +
                ", type=" + type +
                ", point=" + point +
                '}';
    }

    /**
     * Loads the resources of geometry if necessary. It should be called just inside an OpenGL context, otherwise it may crash
     */
    /*
    public Image getTexture() {
        return texture;
    }
     */
}
