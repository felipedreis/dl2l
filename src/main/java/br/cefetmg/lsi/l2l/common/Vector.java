package br.cefetmg.lsi.l2l.common;

/**
 * Created by felipe on 02/01/17.
 */
public class Vector extends Point {

    public static Vector fromPolar(double angle, double module) {
        return new Vector(module * Math.cos(angle), module * Math.sin(angle));
    }

    public Vector(double x, double y) {
        super(x, y);
    }

    public Vector(double x, double y, double z) {
        super(x, y, z);
    }

    public double dot(Vector v) {
        return x*v.x + y*v.y + z*v.z;
    }

    public double size() {
        return distance(Point.O);
    }
}
