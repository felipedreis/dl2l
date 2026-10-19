package br.cefetmg.lsi.l2l.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class SeedsTest {

    @Test
    public void deriveIsAPureFunctionOfRootAndParts() {
        assertEquals(Seeds.derive(42, "creature", 7L, "memoryFilter"),
                Seeds.derive(42, "creature", 7L, "memoryFilter"));
    }

    @Test
    public void deriveIsPinnedAcrossJvmsAndJdks() {
        // Guards against anyone swapping in String.hashCode or a JDK-dependent mixer: seeds
        // recorded in a trial manifest must mean the same stream next year.
        assertEquals(Seeds.derive(1, "a"), Seeds.derive(1, "a"));
        assertEquals(PINNED, Seeds.derive(42, "creature", 7L, "memoryFilter"));
    }

    private static final long PINNED = 2876228543150550078L;

    @Test
    public void differentRootsPartsAndOrdersGiveDifferentSeeds() {
        long base = Seeds.derive(42, "creature", 7L, "memoryFilter");
        assertNotEquals(base, Seeds.derive(43, "creature", 7L, "memoryFilter"));
        assertNotEquals(base, Seeds.derive(42, "creature", 8L, "memoryFilter"));
        assertNotEquals(base, Seeds.derive(42, "creature", 7L, "randomFilter"));
        assertNotEquals(Seeds.derive(42, "a", "b"), Seeds.derive(42, "b", "a"));
    }

    @Test
    public void freshSeedsDiffer() {
        assertNotEquals(Seeds.fresh(), Seeds.fresh());
    }
}
