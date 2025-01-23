package br.cefetmg.lsi.l2l.common;

import java.io.Serializable;
import java.util.Objects;

/**
 * Created by felipe on 02/01/17.
 */
public class Point implements Serializable {

    public static Point O = new Point(0, 0, 0);

    public final double x;
    public final double y;
    public final double z;


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

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) return false;
        Point point = (Point) o;
        return Double.compare(x, point.x) == 0 && Double.compare(y, point.y) == 0 && Double.compare(z, point.z) == 0;
    }

    @Override
    public int hashCode() {
        return Objects.hash(x, y, z);
    }

    @Override
    public String toString() {
        return "(" + x + ", " + y + ", " + z + ")";
    }
}
