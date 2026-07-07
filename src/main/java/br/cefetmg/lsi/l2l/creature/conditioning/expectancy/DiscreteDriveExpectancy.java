package br.cefetmg.lsi.l2l.creature.conditioning.expectancy;

import br.cefetmg.lsi.l2l.creature.common.ActionType;
import br.cefetmg.lsi.l2l.world.WorldObjectType;

/**
 * DISCRETE expectancy variant: keys on {@code (dominantDrive, target, action)} only. It is blind
 * to the dominant drive's magnitude, so it predicts the same reward for eating whether starving
 * or nearly sated — the irreducible error that the CONTINUOUS variant is designed to remove.
 */
public class DiscreteDriveExpectancy extends RunningMeanExpectancy {

    public DiscreteDriveExpectancy() {
        super();
    }

    public DiscreteDriveExpectancy(double alpha) {
        super(alpha);
    }

    @Override
    protected String key(ExpectancyContext ctx, WorldObjectType target, ActionType action) {
        return ctx.dominantDriveName() + '|' + targetName(target) + '|' + action.name();
    }
}
