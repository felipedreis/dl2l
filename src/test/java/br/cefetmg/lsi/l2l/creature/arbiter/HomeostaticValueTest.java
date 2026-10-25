package br.cefetmg.lsi.l2l.creature.arbiter;

import br.cefetmg.lsi.l2l.common.Constants;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HomeostaticValueTest {

    private static final double H_STAR = 0.18;
    private static final double TOL = 0.005;

    /** Two-drive instance matching the arbitration paper's worked example. */
    private static HomeostaticValue twoDrives(double... weights) {
        double[] w = weights.length == 0 ? new double[]{1, 1} : weights;
        return new HomeostaticValue(new double[]{H_STAR, H_STAR}, w);
    }

    @Test
    void paperExampleTwoDrives() {
        HomeostaticValue v = twoDrives();
        double[] k = {-2, 0};
        assertEquals(1.95, v.value(k, new double[]{5, 1}), TOL);
        assertEquals(0.07, v.value(k, new double[]{1, 5}), TOL);
    }

    @Test
    void deficitIsZeroAtOrBelowSetPointAndIgnoresBelowSetPointDrives() {
        HomeostaticValue v = HomeostaticValue.defaults();
        assertEquals(0.0, v.deficit(new double[]{0.0, 0.1, H_STAR, 0.17}), 1e-12);
        // Only the 0.5 drive is above H*, so d = 0.5 - 0.18 = 0.32 regardless of the other drive.
        assertEquals(0.32, twoDrives().deficit(new double[]{0.0, 0.5}), 1e-12);
    }

    @Test
    void zeroOutcomeIsWorthNothing() {
        assertEquals(0.0, twoDrives().value(new double[]{0, 0}, new double[]{5, 1}), 1e-12);
    }

    @Test
    void reliefNeverHurtsAndHarmfulOutcomeDoes() {
        HomeostaticValue v = twoDrives();
        double[] h = {5, 1};
        assertTrue(v.value(new double[]{-2, 0}, h) >= 0);
        assertTrue(v.value(new double[]{0, -0.5}, h) >= 0);
        assertTrue(v.value(new double[]{-1, -1}, h) >= 0);
        assertTrue(v.value(new double[]{2, 0}, h) < 0);
    }

    @Test
    void weightScalesReliefOfThatDrive() {
        double[] h = {5, 1};
        double[] k = {-2, 0};
        // w = (2, 1): excess (4.82, 0.82); d = 9.6748 before, 5.6993 after -> V ~ 3.976.
        assertEquals(3.976, twoDrives(2, 1).value(k, h), TOL);
        assertTrue(twoDrives(2, 1).value(k, h) > twoDrives().value(k, h));
    }

    @Test
    void zeroWeightDriveIsIgnored() {
        assertEquals(0.0, twoDrives(0, 1).value(new double[]{-2, 0}, new double[]{5, 1}), 1e-12);
    }

    @Test
    void reliefOnDriveBelowSetPointIsWorthZero() {
        assertEquals(0.0, twoDrives().value(new double[]{0, -0.5}, new double[]{5, 0.1}), 1e-12);
    }

    @Test
    void dimensionMismatchIsRejected() {
        HomeostaticValue v = twoDrives();
        assertThrows(IllegalArgumentException.class, () -> v.deficit(new double[]{1, 2, 3}));
        assertThrows(IllegalArgumentException.class, () -> v.value(new double[]{1}, new double[]{1, 2}));
        assertThrows(IllegalArgumentException.class, () -> v.value(new double[]{1, 2}, new double[]{1}));
        assertThrows(IllegalArgumentException.class,
                () -> new HomeostaticValue(new double[]{H_STAR, H_STAR}, new double[]{1}));
    }

    @Test
    void nonFiniteInputsAreRejected() {
        HomeostaticValue v = twoDrives();
        assertThrows(IllegalArgumentException.class, () -> v.deficit(new double[]{Double.NaN, 1}));
        assertThrows(IllegalArgumentException.class, () -> v.value(new double[]{Double.NaN, 0}, new double[]{1, 1}));
        assertThrows(IllegalArgumentException.class,
                () -> v.value(new double[]{0, 0}, new double[]{Double.POSITIVE_INFINITY, 1}));
    }

    @Test
    void invalidConstructorArgumentsAreRejected() {
        double[] sp = {H_STAR, H_STAR};
        assertThrows(IllegalArgumentException.class, () -> new HomeostaticValue(sp, new double[]{-1, 1}));
        assertThrows(IllegalArgumentException.class, () -> new HomeostaticValue(sp, new double[]{Double.NaN, 1}));
        assertThrows(IllegalArgumentException.class,
                () -> new HomeostaticValue(new double[]{Double.NaN, H_STAR}, new double[]{1, 1}));
        assertThrows(IllegalArgumentException.class, () -> new HomeostaticValue(null, new double[]{1, 1}));
        assertThrows(IllegalArgumentException.class, () -> new HomeostaticValue(sp, null));
    }

    @Test
    void defaultsSetPointFollowsHomeostaticBandConstant() {
        // Deficit of a single drive sitting 1.0 above the band edge is exactly 1.0.
        double[] h = {Constants.MIN_AROUSAL_LEVEL + 1.0, 0, 0, 0};
        assertEquals(1.0, HomeostaticValue.defaults().deficit(h), 1e-12);
    }
}
