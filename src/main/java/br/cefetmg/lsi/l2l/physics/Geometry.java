package br.cefetmg.lsi.l2l.physics;


import br.cefetmg.lsi.l2l.common.Point;
import org.newdawn.slick.geom.Rectangle;

/**
 * Created by felipe on 20/02/17.
 */
public interface Geometry {

    double getX();

    double getY();

    Point getPoint();

    Rectangle getBoundingBox();
}
