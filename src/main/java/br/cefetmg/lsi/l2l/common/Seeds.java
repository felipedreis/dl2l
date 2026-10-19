package br.cefetmg.lsi.l2l.common;

import java.nio.charset.StandardCharsets;

/**
 * Derives independent, reproducible random seeds from one root seed (issue #95).
 *
 * <p>{@link #derive} is a pure function of the root and the parts, so a stream's seed depends on
 * <em>what</em> it belongs to (e.g. creature 7's memory filter), never on how many creatures or
 * components were created before it, and it is the same on every JVM. Strings are hashed from
 * their UTF-8 bytes (not {@code String.hashCode}) so the result does not depend on the JDK.
 */
public final class Seeds {

    private Seeds() {}

    /** A time-based seed for runs that were not given one; callers must log and record it. */
    public static long fresh() {
        return mix(System.nanoTime() ^ Double.doubleToLongBits(Math.random()));
    }

    public static long derive(long root, Object... parts) {
        long h = mix(root);
        for (Object part : parts) {
            long p = (part instanceof Number n) ? n.longValue() : fnv1a(String.valueOf(part));
            h = mix(h ^ mix(p + 0x9E3779B97F4A7C15L));
        }
        return h;
    }

    /** SplitMix64 finalizer. */
    private static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    private static long fnv1a(String s) {
        long h = 0xCBF29CE484222325L;
        for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
            h = (h ^ (b & 0xff)) * 0x100000001B3L;
        }
        return h;
    }
}
