package br.cefetmg.lsi.l2l.common;

import org.newdawn.slick.geom.SlickPoint;

import java.io.Serializable;

/**
 * Created by felipe on 02/01/17.
 */
public class Point implements Serializable {

    public static Point O = new Point(0, 0, 0);

    public final double x;
    public final double y;
    public final double z;

    public Point(SlickPoint slickPoint) {
        this.x = slickPoint.getX();
        this.y =slickPoint.getY();
        z = 0.;
    }

    public Point(double x, double y) {
        this.x = x;
        this.y = y;
        this.z = 0.;
    }

    public Point(double x, double y, double z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public double distance(Point o) {

        return Math.sqrt(
                Math.pow(x - o.x, 2) +
                Math.pow(y - o.y, 2) +
                Math.pow(z - o.z, 2));
    }

    public Point move(Vector v) {
        return new Point(x + v.x, y + v.y, z + v.z);
    }

    /**
     * return the angle between two points in the xy plane
     * @param p the second point
     * @return the angle between current point and p in radians
     */
    public double angleAlpha(Point p) {
        return Math.atan2(y - p.y, x - p.x);
    }

    public double getX() {
        return x;
    }

    public double getY() {
        return y;
    }

    public double getZ() {
        return z;
    }

    @Override
    public String toString() {
        return "Point{" +
            "x=" + x +
            ", y=" + y +
            '}';
    }
}
