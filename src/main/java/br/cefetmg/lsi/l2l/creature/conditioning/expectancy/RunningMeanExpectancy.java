package br.cefetmg.lsi.l2l.creature.conditioning.expectancy;

import br.cefetmg.lsi.l2l.common.Constants;
import br.cefetmg.lsi.l2l.creature.common.ActionType;
import br.cefetmg.lsi.l2l.world.WorldObjectType;

import java.util.HashMap;
import java.util.Map;

/**
 * Shared base for the symbolic expectancy variants: a table of expected rewards keyed by a
 * String, updated with the Rescorla-Wagner rule {@code v <- v + alpha (reward - v)}. Subclasses
 * only define how a {@code (ctx, target, action)} triple is reduced to a key — that key choice
 * is exactly the discrete-vs-continuous difference being compared.
 */
public abstract class RunningMeanExpectancy implements ExpectancyPredictor {

    private final Map<String, Double> values = new HashMap<>();
    private final double alpha;

    protected RunningMeanExpectancy() {
        this(Constants.EXPECTANCY_ALPHA);
    }

    protected RunningMeanExpectancy(double alpha) {
        this.alpha = alpha;
    }

    /** Reduce the situation to the lookup key that defines this variant's granularity. */
    protected abstract String key(ExpectancyContext ctx, WorldObjectType target, ActionType action);

    @Override
    public double expected(ExpectancyContext ctx, WorldObjectType target, ActionType action) {
        return values.getOrDefault(key(ctx, target, action), Constants.EXPECTANCY_NEUTRAL_PRIOR);
    }

    @Override
    public void observe(ExpectancyContext ctx, WorldObjectType target, ActionType action, double reward) {
        String k = key(ctx, target, action);
        double v = values.getOrDefault(k, Constants.EXPECTANCY_NEUTRAL_PRIOR);
        values.put(k, v + alpha * (reward - v));
    }

    /** Stable string for a possibly-null target, so keys survive across instances. */
    protected static String targetName(WorldObjectType target) {
        return target == null ? "none" : target.name();
    }
}
