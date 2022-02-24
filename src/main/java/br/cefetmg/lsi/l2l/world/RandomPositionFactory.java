package br.cefetmg.lsi.l2l.world;

import br.cefetmg.lsi.l2l.common.Point;

import java.util.Random;

/**
 * Created by felipe on 04/04/17.
 */
public class RandomPositionFactory implements PositionFactory {
    private Random random;
    private Point worldBoundaries;

    public RandomPositionFactory(Point worldBoundaries) {
        random = new Random(System.currentTimeMillis());
        this.worldBoundaries = worldBoundaries;
    }

    @Override
    public Point nextPosition() {
        return new Point(random.nextDouble() * worldBoundaries.x, random.nextDouble() * worldBoundaries.y);
    }
}
