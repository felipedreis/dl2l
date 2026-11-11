package br.cefetmg.lsi.l2l.creature.arbiter;

import br.cefetmg.lsi.l2l.common.Constants;
import br.cefetmg.lsi.l2l.creature.common.Perception;
import br.cefetmg.lsi.l2l.creature.components.FullAppraisal;
import br.cefetmg.lsi.l2l.world.Self;
import br.cefetmg.lsi.l2l.world.WorldObjectType;

import java.util.List;

/**
 * The fixed, hand-built engram key of the uncertainty arbitration paper (section 4.3, issue #112):
 * <pre>k = [ z(p) / sqrt(n_p) ; lambda * z(h) / sqrt(n_h) ]</pre>
 * No learned parts, so a key stored at birth stays valid for the creature's whole life.
 *
 * <p>The percept {@code p} is in the creature's own frame, {@code n_p = 11} features: a one-hot of the object type
 * ({@link #OBJECT_TYPES}, 7), the distance over the vision radius (clipped to [0, 1]), the sine and cosine of the
 * bearing, and a contact flag (1 when the object is not the creature itself and is within
 * {@link FullAppraisal#contactDistance}). The internal state {@code h} is the four drive levels in
 * {@link FocusTracker#DRIVES} order, {@code n_h = 4}.
 *
 * <p>{@code z(.)} standardises each feature with fixed population statistics. The paper says they ship in
 * {@code model_contract.json}; that file holds none, so they are the constants below: assumptions about the
 * population, not measurements, chosen so that no feature dominates a distance
 * (docs/plans/issue-112-engram-key-encoder.md).
 *
 * <p>{@code lambda} weights the internal block against the percept block: 0 matches on what the creature sees only,
 * 1 gives both blocks the same weight. A3 and A5 use {@link #DEFAULT_LAMBDA} = 0.5 (the paper's value; the issue text
 * said 0).
 *
 * <p>Immutable and stateless: safe to share.
 */
public final class EngramKeyEncoder {

    /** The paper's value, used by A3 and A5. */
    public static final double DEFAULT_LAMBDA = 0.5;

    /** One-hot order. {@code self} is the creature itself (WANDER, SLEEP). */
    public static final List<String> OBJECT_TYPES =
            List.of("RED_APPLE", "GREEN_APPLE", "GRAY_APPLE", "ROTTEN_APPLE", "CACTUS", "ALOE", "self");

    /** Percept features: one-hot types, distance ratio, sin, cos, contact. */
    public static final int PERCEPT_DIM = OBJECT_TYPES.size() + 4;
    /** Internal-state features: the four drives. */
    public static final int INTERNAL_DIM = FocusTracker.DRIVES.size();
    /** Length of a key. */
    public static final int KEY_DIM = PERCEPT_DIM + INTERNAL_DIM;

    // Fixed population statistics (assumptions). One-hot: uniform over the types.
    static final double ONE_HOT_MEAN = 1.0 / OBJECT_TYPES.size();
    static final double ONE_HOT_SD = Math.sqrt(ONE_HOT_MEAN * (1 - ONE_HOT_MEAN));
    // Distance ratio: uniform on [0, 1].
    static final double RATIO_MEAN = 0.5;
    static final double RATIO_SD = Math.sqrt(1.0 / 12);
    // sin and cos of a uniform bearing.
    static final double TRIG_MEAN = 0;
    static final double TRIG_SD = Math.sqrt(0.5);
    // Contact: a tenth of the perceptions, Bernoulli.
    static final double CONTACT_MEAN = 0.1;
    static final double CONTACT_SD = Math.sqrt(CONTACT_MEAN * (1 - CONTACT_MEAN));
    // Drive level: uniform on [0, MAX_AROUSAL_LEVEL].
    static final double LEVEL_MEAN = Constants.MAX_AROUSAL_LEVEL / 2;
    static final double LEVEL_SD = Constants.MAX_AROUSAL_LEVEL / Math.sqrt(12);

    private final double lambda;

    /** {@link #DEFAULT_LAMBDA}. */
    public EngramKeyEncoder() {
        this(DEFAULT_LAMBDA);
    }

    /**
     * @param lambda weight of the internal block; finite and non-negative
     * @throws IllegalArgumentException if {@code lambda} is not finite and non-negative
     */
    public EngramKeyEncoder(double lambda) {
        if (!Double.isFinite(lambda) || lambda < 0) {
            throw new IllegalArgumentException("lambda must be finite and non-negative: " + lambda);
        }
        this.lambda = lambda;
    }

    public double lambda() {
        return lambda;
    }

    /**
     * @param perception the percept of the candidate or of the executed action; not null
     * @param h          drive levels in {@link FocusTracker#DRIVES} order; finite; not mutated
     * @return the key, {@link #KEY_DIM} entries
     * @throws IllegalArgumentException if {@code perception} is null, or {@code h} has the wrong size or a
     *                                  non-finite entry
     */
    public double[] encode(Perception perception, double[] h) {
        if (perception == null) {
            throw new IllegalArgumentException("perception must not be null");
        }
        if (h == null || h.length != INTERNAL_DIM) {
            throw new IllegalArgumentException("h must have " + INTERNAL_DIM + " entries");
        }
        for (double level : h) {
            if (!Double.isFinite(level)) {
                throw new IllegalArgumentException("h must be finite");
            }
        }
        WorldObjectType type = perception.objectType.getOrElse(null);
        String typeName = type == null ? null : type.name();
        double perceptScale = 1.0 / Math.sqrt(PERCEPT_DIM);
        double[] k = new double[KEY_DIM];
        for (int i = 0; i < OBJECT_TYPES.size(); i++) {
            double x = OBJECT_TYPES.get(i).equals(typeName) ? 1 : 0;
            k[i] = perceptScale * (x - ONE_HOT_MEAN) / ONE_HOT_SD;
        }
        int at = OBJECT_TYPES.size();
        double ratio = Math.max(0, Math.min(1, perception.distance / Constants.DEFAULT_VISION_FIELD_RADIUS));
        k[at++] = perceptScale * (ratio - RATIO_MEAN) / RATIO_SD;
        k[at++] = perceptScale * (Math.sin(perception.angle) - TRIG_MEAN) / TRIG_SD;
        k[at++] = perceptScale * (Math.cos(perception.angle) - TRIG_MEAN) / TRIG_SD;
        boolean contact = type != null && !(type instanceof Self)
                && perception.distance <= FullAppraisal.contactDistance(type);
        k[at++] = perceptScale * ((contact ? 1 : 0) - CONTACT_MEAN) / CONTACT_SD;
        double internalScale = lambda / Math.sqrt(INTERNAL_DIM);
        for (double level : h) {
            k[at++] = internalScale * (level - LEVEL_MEAN) / LEVEL_SD;
        }
        return k;
    }
}
