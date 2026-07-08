package br.cefetmg.lsi.l2l.creature.testing;

import br.cefetmg.lsi.l2l.cluster.settings.LearningSettings;
import br.cefetmg.lsi.l2l.common.Constants;
import br.cefetmg.lsi.l2l.common.Point;
import br.cefetmg.lsi.l2l.common.SequentialId;
import br.cefetmg.lsi.l2l.creature.bd.ActionSelectionType;
import br.cefetmg.lsi.l2l.creature.common.ActionType;
import br.cefetmg.lsi.l2l.creature.conditioning.expectancy.ExpectancyMode;
import br.cefetmg.lsi.l2l.stimuli.CorticalStimulus;
import br.cefetmg.lsi.l2l.stimuli.LuminousStimulus;
import br.cefetmg.lsi.l2l.stimuli.OrexinergicStimulus;
import br.cefetmg.lsi.l2l.world.FruitType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Functional (whole-pipeline) tests for the orexin wakefulness gate through a TestingCreature:
 * (1) PartialAppraisal emits OrexinergicStimulus each cycle when enabled;
 * (2) a rested creature's orexin gates SLEEP out of the action set;
 * (3) an exhausted creature's orexin falls below the gate threshold and SLEEP is allowed back in.
 * ActionTendencyFilter is OFF throughout so orexin alone is responsible for any SLEEP suppression.
 */
class OrexinFunctionalTest {

    private static LearningSettings orexinOn() {
        return new LearningSettings(
                true, false,
                List.of(ActionSelectionType.AFFORDANCE, ActionSelectionType.RANDOM),
                true, ExpectancyMode.DISCRETE,
                true,   // neuromodulationEnabled
                false,  // actionTendencyEnabled — OFF: orexin must do the work alone
                true,   // orexinEnabled
                false   // endocrineEnabled
        );
    }

    private static LearningSettings orexinOff() {
        return new LearningSettings(
                true, false,
                List.of(ActionSelectionType.AFFORDANCE, ActionSelectionType.RANDOM),
                true, ExpectancyMode.DISCRETE,
                true,   // neuromodulationEnabled
                false,  // actionTendencyEnabled
                false,  // orexinEnabled — OFF
                false
        );
    }

    // ---------------------------------------------------------------------------------------
    // 1. Message delivery
    // ---------------------------------------------------------------------------------------

    @Test
    void orexin_stimulus_delivered_to_neuromodulator_pool_when_enabled() {
        TestingHarness h = TestingHarness.builder().learningSettings(orexinOn()).build();

        for (int i = 0; i < 5; i++) h.tick();

        assertTrue(h.neuromodulatorRecorder().hasAny(OrexinergicStimulus.class),
                "PartialAppraisal must emit OrexinergicStimulus each cycle when orexinEnabled");
    }

    @Test
    void no_orexin_stimulus_when_disabled() {
        TestingHarness h = TestingHarness.builder().learningSettings(orexinOff()).build();

        for (int i = 0; i < 5; i++) h.tick();

        assertFalse(h.neuromodulatorRecorder().hasAny(OrexinergicStimulus.class),
                "no OrexinergicStimulus should be emitted when orexinEnabled=false");
    }

    // ---------------------------------------------------------------------------------------
    // 2. Behavioural gate (statistical, N independent first decisions)
    // ---------------------------------------------------------------------------------------

    @Test
    void orexin_gates_sleep_out_when_creature_is_rested() {
        // Rested creature: sleep pressure at MIN_AROUSAL_LEVEL → orexin release ≈ 1.0
        // → orexinTonic quickly reaches fixed point (≈ 33) >> gate threshold (0.4)
        // → SLEEP should never be selected.
        int N = 500;
        double sleepShare = sleepShareOnFirstDecision(orexinOn(), Constants.MIN_AROUSAL_LEVEL, N);
        assertEquals(0.0, sleepShare, 0.0,
                "a rested creature with orexin enabled should never pick SLEEP; share=" + sleepShare);
    }

    @Test
    void orexin_allows_sleep_when_creature_is_exhausted() {
        // Exhausted creature: sleep pressure set to MAX before any ticks → orexin release = 0
        // on every cycle → orexinTonic stays at/near 0 (below gate threshold of 0.4)
        // → SLEEP must appear in the action set and be selectable.
        int N = 200;
        int sleeps = 0, total = 0;
        for (int i = 0; i < N; i++) {
            TestingHarness h = TestingHarness.builder().learningSettings(orexinOn()).build();

            // Force maximum sleep pressure BEFORE priming so orexin never accumulates.
            double currentSleep = h.creature().emotions().getLevel(Constants.SLEEP);
            h.creature().emotions().regulate(Constants.SLEEP,
                    Constants.MAX_AROUSAL_LEVEL - 0.1 - currentSleep);

            // Run a few ticks so NeuromodulatorSystem processes the orexin(0) messages.
            for (int t = 0; t < 5; t++) h.tick();

            Point pos = h.creature().getPosition();
            SequentialId sid = new SequentialId(300_000L + i * 3L);
            h.injectLuminous(new LuminousStimulus(sid, sid.next(), FruitType.RED_APPLE,
                    new Point(pos.x + 100, pos.y)));

            CorticalStimulus c = h.effectorCortexRecorder().lastOf(CorticalStimulus.class);
            if (c == null) continue;
            total++;
            if (c.action == ActionType.SLEEP) sleeps++;
        }
        assertTrue(sleeps > 0,
                "an exhausted creature's orexin should stay below the gate, allowing SLEEP to be selected");
    }

    /**
     * Fraction of N fresh creatures whose first decision (fruit at distance) is SLEEP.
     * Sleep pressure is set to {@code sleepLevel} before priming so orexin builds up
     * against that pressure level from the start.
     */
    private static double sleepShareOnFirstDecision(LearningSettings settings, double sleepLevel, int N) {
        int sleeps = 0, total = 0;
        for (int i = 0; i < N; i++) {
            TestingHarness h = TestingHarness.builder().learningSettings(settings).build();

            // Set sleep pressure first, then prime so orexin stabilises at the right level.
            double currentSleep = h.creature().emotions().getLevel(Constants.SLEEP);
            h.creature().emotions().regulate(Constants.SLEEP, sleepLevel - currentSleep);

            for (int t = 0; t < 20; t++) h.tick();

            Point pos = h.creature().getPosition();
            SequentialId sid = new SequentialId(200_000L + i * 3L);
            h.injectLuminous(new LuminousStimulus(sid, sid.next(), FruitType.RED_APPLE,
                    new Point(pos.x + 100, pos.y)));

            CorticalStimulus c = h.effectorCortexRecorder().lastOf(CorticalStimulus.class);
            if (c == null) continue;
            total++;
            if (c.action == ActionType.SLEEP) sleeps++;
        }
        return total == 0 ? 0.0 : sleeps / (double) total;
    }
}
