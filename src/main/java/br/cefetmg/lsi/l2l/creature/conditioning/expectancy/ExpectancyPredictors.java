package br.cefetmg.lsi.l2l.creature.conditioning.expectancy;

/**
 * Factory for {@link ExpectancyPredictor} instances, so callers select a variant by
 * {@link ExpectancyMode} without duplicating construction logic.
 */
public final class ExpectancyPredictors {

    private ExpectancyPredictors() {
    }

    public static ExpectancyPredictor forMode(ExpectancyMode mode) {
        return switch (mode) {
            case CONTINUOUS -> new ContinuousDriveExpectancy();
            case DISCRETE   -> new DiscreteDriveExpectancy();
            case JEPA       -> throw new IllegalArgumentException(
                    "ExpectancyMode.JEPA requires a WorldModelFilter — use CreatureActor wiring, not forMode()");
        };
    }
}
