package br.cefetmg.lsi.l2l.world;

import br.cefetmg.lsi.l2l.common.Point;
import br.cefetmg.lsi.l2l.common.SequentialId;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Issue #95: initial conditions are a function of the seed. */
public class RandomPositionFactoryTest {

    private static final Point WORLD = new Point(1000, 800);

    private static List<Point> positionsFor(RandomPositionFactory f, int n) {
        List<Point> out = new ArrayList<>();
        for (long k = 1; k <= n; k++) out.add(f.positionFor(new SequentialId(k)));
        return out;
    }

    @Test
    public void sameSeedGivesIdenticalWorlds() {
        assertEquals(positionsFor(new RandomPositionFactory(WORLD, 7), 200),
                positionsFor(new RandomPositionFactory(WORLD, 7), 200));
    }

    @Test
    public void differentSeedsGiveDifferentWorlds() {
        assertNotEquals(positionsFor(new RandomPositionFactory(WORLD, 7), 200),
                positionsFor(new RandomPositionFactory(WORLD, 8), 200));
    }

    @Test
    public void positionOfAnIdIsIndependentOfCallOrderAndOfOtherHolders() {
        RandomPositionFactory a = new RandomPositionFactory(WORLD, 7);
        RandomPositionFactory b = new RandomPositionFactory(WORLD, 7);
        a.positionFor(new SequentialId(1));
        a.nextPosition();
        assertEquals(b.positionFor(new SequentialId(5)), a.positionFor(new SequentialId(5)));
    }

    @Test
    public void positionsStayInsideTheWorldAndDifferBetweenIds() {
        RandomPositionFactory f = new RandomPositionFactory(WORLD, 7);
        List<Point> ps = positionsFor(f, 200);
        assertEquals(200, ps.stream().distinct().count());
        for (Point p : ps) {
            assertTrue(p.x >= 0 && p.x < WORLD.x && p.y >= 0 && p.y < WORLD.y);
        }
    }

    @Test
    public void nextPositionStreamIsReproducible() {
        RandomPositionFactory a = new RandomPositionFactory(WORLD, 3), b = new RandomPositionFactory(WORLD, 3);
        for (int i = 0; i < 50; i++) assertEquals(a.nextPosition(), b.nextPosition());
    }
}
