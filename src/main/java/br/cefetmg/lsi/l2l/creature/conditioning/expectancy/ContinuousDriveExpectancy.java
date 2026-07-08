package br.cefetmg.lsi.l2l.creature.conditioning.expectancy;

import br.cefetmg.lsi.l2l.common.Constants;
import br.cefetmg.lsi.l2l.creature.common.ActionType;
import br.cefetmg.lsi.l2l.world.WorldObjectType;

/**
 * CONTINUOUS expectancy variant: keys on {@code (dominantDrive, levelBucket, target, action)},
 * where the dominant drive's arousal level is discretised into
 * {@link Constants#EXPECTANCY_LEVEL_BUCKETS} buckets over
 * {@code [MIN_AROUSAL_LEVEL, MAX_AROUSAL_LEVEL]}. This lets it capture how the reward depends on
 * the drive magnitude (e.g. eating relieves more hunger when starving) — the axis the DISCRETE
 * variant cannot represent.
 */
public class ContinuousDriveExpectancy extends RunningMeanExpectancy {

    private final int buckets;

    public ContinuousDriveExpectancy() {
        this(Constants.EXPECTANCY_ALPHA, Constants.EXPECTANCY_LEVEL_BUCKETS);
    }

    public ContinuousDriveExpectancy(double alpha, int buckets) {
        super(alpha);
        this.buckets = buckets;
    }

    @Override
    protected String key(ExpectancyContext ctx, WorldObjectType target, ActionType action) {
        return ctx.dominantDriveName() + '|' + bucket(ctx.dominantDriveLevel())
                + '|' + targetName(target) + '|' + action.name();
    }

    /** Map an arousal level to a bucket index in [0, buckets-1], clamping out-of-range levels. */
    private int bucket(double level) {
        double span = Constants.MAX_AROUSAL_LEVEL - Constants.MIN_AROUSAL_LEVEL;
        double norm = (level - Constants.MIN_AROUSAL_LEVEL) / span;
        int idx = (int) Math.floor(norm * buckets);
        if (idx < 0) return 0;
        if (idx >= buckets) return buckets - 1;
        return idx;
    }
}
