package br.cefetmg.lsi.l2l.world;

import br.cefetmg.lsi.l2l.common.Point;
import br.cefetmg.lsi.l2l.common.SequentialId;
import br.cefetmg.lsi.l2l.common.Seeds;

import java.util.Random;

/**
 * Created by felipe on 04/04/17.
 */
public class RandomPositionFactory implements PositionFactory {
    private final long seed;
    private final Random random;
    private final Point worldBoundaries;

    public RandomPositionFactory(Point worldBoundaries, long seed) {
        this.seed = seed;
        this.random = new Random(Seeds.derive(seed, "position-stream"));
        this.worldBoundaries = worldBoundaries;
    }

    @Override
    public Point nextPosition() {
        return at(random);
    }

    /**
     * A function of the seed and the id alone: every holder JVM builds its own factory from the
     * same seed, so a shared stateful stream would hand the same positions to different objects.
     */
    @Override
    public Point positionFor(SequentialId id) {
        return at(new Random(Seeds.derive(seed, "position", id.key, id.sequential)));
    }

    private Point at(Random r) {
        return new Point(r.nextDouble() * worldBoundaries.x, r.nextDouble() * worldBoundaries.y);
    }
}
