package br.cefetmg.lsi.l2l.creature.arbiter;

import br.cefetmg.lsi.l2l.common.SequentialId;
import br.cefetmg.lsi.l2l.creature.common.Perception;
import br.cefetmg.lsi.l2l.world.FruitType;
import br.cefetmg.lsi.l2l.world.PlantType;
import br.cefetmg.lsi.l2l.world.Self;
import br.cefetmg.lsi.l2l.world.WorldObjectType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Tests of {@link EngramKeyEncoder} (issue #112): standardisation, block normalisation, lambda. */
class EngramKeyEncoderTest {

    private static final double TOL = 1e-12;
    private static final double[] H = {1, 2, 3, 4};
    private static final int RATIO = 7, SIN = 8, COS = 9, CONTACT = 10, H0 = 11;

    private static Perception p(WorldObjectType type, double distance, double angle) {
        return new Perception(type, new SequentialId(1), distance, angle);
    }

    private static double dist(double[] a, double[] b) {
        double s = 0;
        for (int i = 0; i < a.length; i++) {
            s += (a[i] - b[i]) * (a[i] - b[i]);
        }
        return Math.sqrt(s);
    }

    @Test
    void keyHasPerceptBlockThenInternalBlock() {
        assertEquals(11, EngramKeyEncoder.PERCEPT_DIM);
        assertEquals(4, EngramKeyEncoder.INTERNAL_DIM);
        assertEquals(15, new EngramKeyEncoder().encode(p(FruitType.RED_APPLE, 50, 0), H).length);
    }

    @Test
    void featuresAreStandardisedWithTheFixedPopulationStatistics() {
        EngramKeyEncoder enc = new EngramKeyEncoder(1.0);
        double s = 1.0 / Math.sqrt(EngramKeyEncoder.PERCEPT_DIM);
        // distance ratio at its population mean (0.5 of the radius) standardises to 0
        double[] mid = enc.encode(p(FruitType.RED_APPLE, 75, 0), H);
        assertEquals(0.0, mid[RATIO], TOL);
        // one population sd above the mean (0.5 + 1/sqrt(12)) standardises to 1, then block-scaled
        double oneSd = (0.5 + Math.sqrt(1.0 / 12)) * 150;
        assertEquals(s, enc.encode(p(FruitType.RED_APPLE, oneSd, 0), H)[RATIO], TOL);
        // a drive level at the population mean (3.5) standardises to 0
        assertEquals(0.0, enc.encode(p(FruitType.RED_APPLE, 75, 0), new double[]{3.5, 3.5, 3.5, 3.5})[H0], TOL);
        // one-hot: the matching entry is above the others, equal entries elsewhere
        double[] red = enc.encode(p(FruitType.RED_APPLE, 75, 0), H);
        assertEquals(red[1], red[2], TOL);
        assertNotEquals(red[0], red[1]);
    }

    @Test
    void distanceIsClippedToTheVisionRadius() {
        EngramKeyEncoder enc = new EngramKeyEncoder();
        assertEquals(enc.encode(p(FruitType.RED_APPLE, 150, 0), H)[RATIO],
                enc.encode(p(FruitType.RED_APPLE, 900, 0), H)[RATIO], TOL);
    }

    @Test
    void bearingIsEncodedAsSineAndCosine() {
        EngramKeyEncoder enc = new EngramKeyEncoder();
        double[] k = enc.encode(p(FruitType.RED_APPLE, 75, Math.PI / 2), H);
        double[] zero = enc.encode(p(FruitType.RED_APPLE, 75, 0), H);
        // sin rises by 1/sd, cos falls by 1/sd, each over sqrt(n_p)
        double step = 1.0 / Math.sqrt(0.5) / Math.sqrt(EngramKeyEncoder.PERCEPT_DIM);
        assertEquals(step, k[SIN] - zero[SIN], TOL);
        assertEquals(-step, k[COS] - zero[COS], TOL);
        // a bearing of +2pi is the same bearing
        assertArrayEquals(zero, enc.encode(p(FruitType.RED_APPLE, 75, 2 * Math.PI), H), 1e-9);
    }

    @Test
    void contactFlagIsSetWithinReachAndNeverForSelf() {
        EngramKeyEncoder enc = new EngramKeyEncoder();
        double reach = 10 + FruitType.RED_APPLE.radius; // mouth radius + object radius
        double near = enc.encode(p(FruitType.RED_APPLE, reach, 0), H)[CONTACT];
        double far = enc.encode(p(FruitType.RED_APPLE, reach + 0.5, 0), H)[CONTACT];
        assertEquals(far + 1.0 / EngramKeyEncoder.CONTACT_SD / Math.sqrt(EngramKeyEncoder.PERCEPT_DIM), near, TOL);
        assertEquals(far, enc.encode(p(Self.get(), 0, 0), H)[CONTACT], TOL);
    }

    @Test
    void lambdaScalesTheInternalBlockOnly() {
        Perception pc = p(FruitType.GREEN_APPLE, 40, 0.3);
        double[] half = new EngramKeyEncoder(0.5).encode(pc, H);
        double[] full = new EngramKeyEncoder(1.0).encode(pc, H);
        for (int i = 0; i < H0; i++) {
            assertEquals(half[i], full[i], TOL, "percept block does not depend on lambda");
        }
        for (int i = H0; i < EngramKeyEncoder.KEY_DIM; i++) {
            assertEquals(2 * half[i], full[i], TOL, "internal block is linear in lambda");
        }
    }

    @Test
    void lambdaZeroIgnoresHowTheCreatureFeels() {
        EngramKeyEncoder enc = new EngramKeyEncoder(0.0);
        Perception pc = p(FruitType.GREEN_APPLE, 40, 0.3);
        assertArrayEquals(enc.encode(pc, new double[]{0, 0, 0, 0}), enc.encode(pc, new double[]{7, 7, 7, 7}), 0.0);
    }

    @Test
    void blocksContributeEquallyWhateverTheirSize() {
        // one standardised unit of difference in a single feature moves the key by 1/sqrt(n) of its block
        EngramKeyEncoder enc = new EngramKeyEncoder(1.0);
        double[] base = enc.encode(p(FruitType.RED_APPLE, 75, 0), new double[]{3.5, 3.5, 3.5, 3.5});
        double sd = 7 / Math.sqrt(12);
        double[] moved = enc.encode(p(FruitType.RED_APPLE, 75, 0), new double[]{3.5 + sd, 3.5, 3.5, 3.5});
        assertEquals(1.0 / Math.sqrt(EngramKeyEncoder.INTERNAL_DIM), dist(base, moved), TOL);
        double[] farther = enc.encode(p(FruitType.RED_APPLE, 75 + Math.sqrt(1.0 / 12) * 150, 0),
                new double[]{3.5, 3.5, 3.5, 3.5});
        assertEquals(1.0 / Math.sqrt(EngramKeyEncoder.PERCEPT_DIM), dist(base, farther), TOL);
    }

    @Test
    void sameInputsGiveTheSameKeyAndInputsAreNotMutated() {
        EngramKeyEncoder enc = new EngramKeyEncoder();
        double[] h = H.clone();
        Perception pc = p(PlantType.CACTUS, 33, 1.1);
        assertArrayEquals(enc.encode(pc, h), enc.encode(pc, h), 0.0);
        assertArrayEquals(H, h, 0.0);
    }

    @Test
    void rejectsBadInputs() {
        EngramKeyEncoder enc = new EngramKeyEncoder();
        assertThrows(IllegalArgumentException.class, () -> enc.encode(null, H));
        assertThrows(IllegalArgumentException.class, () -> enc.encode(p(FruitType.RED_APPLE, 1, 0), null));
        assertThrows(IllegalArgumentException.class, () -> enc.encode(p(FruitType.RED_APPLE, 1, 0), new double[3]));
        assertThrows(IllegalArgumentException.class,
                () -> enc.encode(p(FruitType.RED_APPLE, 1, 0), new double[]{0, 0, 0, Double.NaN}));
        assertThrows(IllegalArgumentException.class, () -> new EngramKeyEncoder(-0.1));
        assertThrows(IllegalArgumentException.class, () -> new EngramKeyEncoder(Double.NaN));
    }
}
