package br.cefetmg.lsi.l2l.creature.conditioning.expectancy;

import br.cefetmg.lsi.l2l.common.Constants;
import br.cefetmg.lsi.l2l.creature.common.ActionType;
import br.cefetmg.lsi.l2l.world.FruitType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the symbolic expectancy predictors. The central contract being verified is that
 * the DISCRETE variant is blind to drive magnitude while the CONTINUOUS variant is not — the gap
 * that the validation experiment measures.
 */
public class ExpectancyPredictorTest {

    private static final ActionType EAT = ActionType.EAT;
    private static final FruitType APPLE = FruitType.RED_APPLE;

    private static ExpectancyContext hungerAt(double level) {
        return new ExpectancyContext(Constants.HUNGER, level);
    }

    @Test
    void unseen_key_returns_neutral_prior() {
        ExpectancyPredictor discrete = new DiscreteDriveExpectancy();
        ExpectancyPredictor continuous = new ContinuousDriveExpectancy();

        assertEquals(Constants.EXPECTANCY_NEUTRAL_PRIOR, discrete.expected(hungerAt(5.0), APPLE, EAT));
        assertEquals(Constants.EXPECTANCY_NEUTRAL_PRIOR, continuous.expected(hungerAt(5.0), APPLE, EAT));
    }

    @Test
    void both_converge_to_constant_reward_per_key() {
        ExpectancyPredictor discrete = new DiscreteDriveExpectancy(0.5);
        ExpectancyPredictor continuous = new ContinuousDriveExpectancy(0.5, Constants.EXPECTANCY_LEVEL_BUCKETS);

        for (int i = 0; i < 100; i++) {
            discrete.observe(hungerAt(6.0), APPLE, EAT, 3.0);
            continuous.observe(hungerAt(6.0), APPLE, EAT, 3.0);
        }

        assertEquals(3.0, discrete.expected(hungerAt(6.0), APPLE, EAT), 1e-6);
        assertEquals(3.0, continuous.expected(hungerAt(6.0), APPLE, EAT), 1e-6);
    }

    @Test
    void discrete_is_blind_to_drive_magnitude() {
        // Same (drive, target, action) but very different levels; DISCRETE collapses them to one key.
        ExpectancyPredictor discrete = new DiscreteDriveExpectancy(0.5);

        // High reward observed when starving, low reward when nearly sated.
        for (int i = 0; i < 200; i++) {
            discrete.observe(hungerAt(6.5), APPLE, EAT, 5.0);
            discrete.observe(hungerAt(0.3), APPLE, EAT, 0.1);
        }

        double whenStarving = discrete.expected(hungerAt(6.5), APPLE, EAT);
        double whenSated    = discrete.expected(hungerAt(0.3), APPLE, EAT);

        // Identical prediction for both levels — it cannot tell them apart.
        assertEquals(whenStarving, whenSated, 1e-9);
    }

    @Test
    void continuous_separates_drive_magnitude() {
        // Same inputs as the discrete blindness test, but CONTINUOUS buckets the level.
        ExpectancyPredictor continuous = new ContinuousDriveExpectancy(0.5, Constants.EXPECTANCY_LEVEL_BUCKETS);

        for (int i = 0; i < 200; i++) {
            continuous.observe(hungerAt(6.5), APPLE, EAT, 5.0);
            continuous.observe(hungerAt(0.3), APPLE, EAT, 0.1);
        }

        double whenStarving = continuous.expected(hungerAt(6.5), APPLE, EAT);
        double whenSated    = continuous.expected(hungerAt(0.3), APPLE, EAT);

        // Distinct buckets learn distinct expectations.
        assertEquals(5.0, whenStarving, 1e-3);
        assertEquals(0.1, whenSated, 1e-3);
        assertTrue(whenStarving > whenSated + 1.0);
    }

    @Test
    void distinct_drives_are_isolated() {
        ExpectancyPredictor discrete = new DiscreteDriveExpectancy(0.5);

        for (int i = 0; i < 100; i++) {
            discrete.observe(new ExpectancyContext(Constants.HUNGER, 5.0), APPLE, EAT, 4.0);
        }

        // Reinforced under HUNGER only — SLEEP key is untouched.
        assertEquals(4.0, discrete.expected(new ExpectancyContext(Constants.HUNGER, 5.0), APPLE, EAT), 1e-6);
        assertEquals(Constants.EXPECTANCY_NEUTRAL_PRIOR,
                discrete.expected(new ExpectancyContext(Constants.SLEEP, 5.0), APPLE, EAT));
    }
}
