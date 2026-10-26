package br.cefetmg.lsi.l2l.creature.arbiter;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EstimateTest {

    @Test
    void acceptsValidValuesAndExposesAccessors() {
        Estimate e = new Estimate(1.5, 0.2, 0.7);
        assertEquals(1.5, e.q(), 0.0);
        assertEquals(0.2, e.se(), 0.0);
        assertEquals(0.7, e.sigma(), 0.0);
    }

    @Test
    void zeroSpreadsAreValid() {
        Estimate e = new Estimate(0.0, 0.0, 0.0);
        assertEquals(0.0, e.se(), 0.0);
        assertEquals(0.0, e.sigma(), 0.0);
    }

    @Test
    void negativeQIsAllowed() {
        Estimate e = new Estimate(-3.25, 0.1, 0.1);
        assertEquals(-3.25, e.q(), 0.0);
    }

    @Test
    void rejectsNonFiniteQ() {
        assertThrows(IllegalArgumentException.class, () -> new Estimate(Double.NaN, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new Estimate(Double.POSITIVE_INFINITY, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new Estimate(Double.NEGATIVE_INFINITY, 0, 0));
    }

    @Test
    void rejectsNonFiniteSe() {
        assertThrows(IllegalArgumentException.class, () -> new Estimate(0, Double.NaN, 0));
        assertThrows(IllegalArgumentException.class, () -> new Estimate(0, Double.POSITIVE_INFINITY, 0));
    }

    @Test
    void rejectsNonFiniteSigma() {
        assertThrows(IllegalArgumentException.class, () -> new Estimate(0, 0, Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> new Estimate(0, 0, Double.POSITIVE_INFINITY));
    }

    @Test
    void rejectsNegativeSeAndSigma() {
        assertThrows(IllegalArgumentException.class, () -> new Estimate(0, -0.1, 0));
        assertThrows(IllegalArgumentException.class, () -> new Estimate(0, 0, -0.1));
    }

    @Test
    void equalityIsByValue() {
        assertEquals(new Estimate(1, 2, 3), new Estimate(1, 2, 3));
        assertEquals(new Estimate(1, 2, 3).hashCode(), new Estimate(1, 2, 3).hashCode());
        assertNotEquals(new Estimate(1, 2, 3), new Estimate(1, 2, 4));
    }

    @Test
    void withSeScaledMultipliesOnlySe() {
        Estimate e = new Estimate(1.5, 0.4, 0.7);
        Estimate scaled = e.withSeScaled(2.5);
        assertEquals(new Estimate(1.5, 1.0, 0.7), scaled);
        // The receiver is immutable and unchanged.
        assertEquals(0.4, e.se(), 0.0);
    }

    @Test
    void withSeScaledByZeroKeepsSeZero() {
        assertEquals(new Estimate(-1, 0, 0.5), new Estimate(-1, 0.3, 0.5).withSeScaled(0));
    }

    @Test
    void withSeScaledRejectsInvalidFactor() {
        Estimate e = new Estimate(1, 1, 1);
        assertThrows(IllegalArgumentException.class, () -> e.withSeScaled(-0.5));
        assertThrows(IllegalArgumentException.class, () -> e.withSeScaled(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> e.withSeScaled(Double.POSITIVE_INFINITY));
    }
}
