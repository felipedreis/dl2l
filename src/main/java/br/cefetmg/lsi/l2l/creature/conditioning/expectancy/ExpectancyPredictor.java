package br.cefetmg.lsi.l2l.creature.conditioning.expectancy;

import br.cefetmg.lsi.l2l.creature.common.ActionType;
import br.cefetmg.lsi.l2l.world.WorldObjectType;

/**
 * A dedicated predictor of the expected reward of doing {@code action} on {@code target} in a
 * given internal {@link ExpectancyContext}. This is the revival of ARTÍFICE's "expectancy"
 * value: it supplies the baseline against which the dopaminergic reward-prediction error
 * {@code rpe = reward - expected} is computed.
 *
 * <p>Two symbolic implementations differ only in what state they condition on
 * ({@link DiscreteDriveExpectancy} vs {@link ContinuousDriveExpectancy}); a learned neural
 * critic (e.g. the JEPA critic head) could later implement the same interface, so the rest of
 * the reward loop is agnostic to how the prediction is produced.
 *
 * <p>Reward convention: {@code reward = -arousalVariation} (positive = drive dropped = good).
 */
public interface ExpectancyPredictor {

    /** Predicted expected reward for {@code (ctx, target, action)}; neutral prior when unseen. */
    double expected(ExpectancyContext ctx, WorldObjectType target, ActionType action);

    /** Update the prediction toward an observed {@code reward} for {@code (ctx, target, action)}. */
    void observe(ExpectancyContext ctx, WorldObjectType target, ActionType action, double reward);
}
