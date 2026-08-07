package br.cefetmg.lsi.l2l.creature.testing;

import br.cefetmg.lsi.l2l.cluster.settings.LearningSettings;
import br.cefetmg.lsi.l2l.common.Constants;
import br.cefetmg.lsi.l2l.common.Point;
import br.cefetmg.lsi.l2l.common.SequentialId;
import br.cefetmg.lsi.l2l.creature.bd.ActionSelectionType;
import br.cefetmg.lsi.l2l.creature.common.Perception;
import br.cefetmg.lsi.l2l.stimuli.CorticalStimulus;
import br.cefetmg.lsi.l2l.stimuli.EmotionalStimulus;
import br.cefetmg.lsi.l2l.stimuli.LuminousStimulus;
import br.cefetmg.lsi.l2l.world.FruitType;
import br.cefetmg.lsi.l2l.world.Self;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Issue #85: perception is state, the tick is the trigger.
 *
 * <p>Before this, a cognitive cycle ran on every message delivery to PartialAppraisal, so
 * the collision detector's asynchronous replies drove cycles of their own on top of the
 * wall-clock heartbeat. Measured consequences: a cycle rate ~9x
 * {@link Constants#TARGET_CYCLE_HZ}, and a perceptual stream that alternated between empty
 * (heartbeat) and non-empty (perception) cycles at ~66 Hz - a stationary fruit appearing and
 * vanishing within milliseconds.
 *
 * <p>These tests pin the resulting contract at component level. The wall-clock consequences
 * (actual rate, actual flip rate against the real scheduler and collision detector) are
 * covered by {@code SimulationCycleRateIntegrationTest}, which the single-threaded harness
 * here cannot express - it has no scheduler at all.
 */
class TickGatedCognitionTest {

    private static LearningSettings settings(boolean tickGated) {
        return new LearningSettings(
                true, false,
                List.of(ActionSelectionType.TARGET_DISTANCE,
                        ActionSelectionType.AFFORDANCE,
                        ActionSelectionType.RANDOM),
                false, br.cefetmg.lsi.l2l.creature.conditioning.expectancy.ExpectancyMode.DISCRETE,
                false, false, false, false, tickGated);
    }

    private static TestingHarness harness(boolean tickGated) {
        return TestingHarness.builder().learningSettings(settings(tickGated)).build();
    }

    private static LuminousStimulus appleAt(TestingHarness h, long seed, double dx) {
        SequentialId a = new SequentialId(seed);
        Point p = h.creature().getPosition();
        return new LuminousStimulus(a, a.next(), FruitType.RED_APPLE, new Point(p.x + dx, p.y));
    }

    // --- 1. perception alone does not cognize -------------------------------------------

    @Test
    void perception_without_a_tick_buffers_and_produces_no_decision() {
        TestingHarness h = harness(true);

        h.injectLuminous(appleAt(h, 90_001L, 60));

        assertTrue(h.partialRecorder().hasAny(br.cefetmg.lsi.l2l.stimuli.ProprioceptiveStimulus.class),
                "the perception must still reach PartialAppraisal - it is buffered, not dropped");
        assertFalse(h.fullRecorder().hasAny(EmotionalStimulus.class),
                "no cognitive cycle may run until a CognitiveTick arrives");
        assertNull(h.effectorCortexRecorder().lastOf(CorticalStimulus.class),
                "and therefore no action may be selected");
    }

    // --- 2. the tick appraises what was buffered ----------------------------------------

    @Test
    void a_tick_after_perception_produces_exactly_one_decision_carrying_it() {
        TestingHarness h = harness(true);

        h.injectLuminous(appleAt(h, 90_002L, 60));
        h.tick();

        List<EmotionalStimulus> emotionals = h.fullRecorder().ofType(EmotionalStimulus.class);
        assertEquals(1, emotionals.size(), "exactly one cycle per tick");
        List<Perception> perceptions = emotionals.get(0).getPerceptions();
        assertEquals(1, perceptions.size());
        assertEquals(FruitType.RED_APPLE, perceptions.get(0).objectType.get(),
                "the cycle must appraise the buffered fruit, not the Self fallback");
    }

    // --- 3. a whole tick window is one scene --------------------------------------------

    @Test
    void perceptions_arriving_between_ticks_are_appraised_as_one_scene() {
        TestingHarness h = harness(true);

        // Three distinct objects, delivered as three separate detector replies would be.
        h.injectLuminous(appleAt(h, 90_003L, 40));
        h.injectLuminous(appleAt(h, 90_004L, 60));
        h.injectLuminous(appleAt(h, 90_005L, 80));
        h.tick();

        List<EmotionalStimulus> emotionals = h.fullRecorder().ofType(EmotionalStimulus.class);
        assertEquals(1, emotionals.size(),
                "three perceptions in one tick window are one cycle, not three");
        assertEquals(3, emotionals.get(0).getPerceptions().size());
        assertTrue(emotionals.get(0).getPerceptions().size() >= Constants.COMPLEX_TASK,
                "a three-object scene must be appraised as a complex task (Yerkes-Dodson "
                        + "inverted-U), which per-delivery cycling could never see");
    }

    // --- 4. the flicker regression ------------------------------------------------------

    @Test
    void a_stationary_object_is_perceived_on_every_consecutive_cycle() {
        // Hungry + action tendencies on so APPROACH is selected deterministically. This
        // matters: SLEEP closes the eye (focus 0 < MIN_VISION_FIELD_OPENING, dropped in
        // Eye.onReceive), which is a genuine empty sensory field rather than the flicker
        // under test, and would make the assertion below ambiguous.
        TestingHarness h = TestingHarness.builder()
                .learningSettings(new LearningSettings(true, false,
                        List.of(ActionSelectionType.AFFORDANCE, ActionSelectionType.RANDOM),
                        false, br.cefetmg.lsi.l2l.creature.conditioning.expectancy.ExpectancyMode.DISCRETE,
                        false, true))
                .build();
        h.creature().emotions().regulate(Constants.HUNGER, 3.0);

        // A physically stationary fruit: the detector reports it once per sweep, and one
        // sweep lands in each tick window.
        int ticks = 10;
        for (int i = 0; i < ticks; i++) {
            h.injectLuminous(appleAt(h, 90_100L + i, 60));
            h.tick();
        }

        List<EmotionalStimulus> emotionals = h.fullRecorder().ofType(EmotionalStimulus.class);
        assertEquals(ticks, emotionals.size(), "one cycle per tick");
        for (int i = 0; i < emotionals.size(); i++) {
            List<Perception> perceptions = emotionals.get(i).getPerceptions();
            assertEquals(1, perceptions.size(), "cycle " + i + " must carry the fruit");
            assertNotEquals(Self.class, perceptions.get(0).objectType.get().getClass(),
                    "cycle " + i + " fell back to Self - the object flickered out of "
                            + "existence while physically stationary");
        }
    }

    // --- 5. liveness: a tick with nothing buffered still cognizes ------------------------

    @Test
    void a_tick_with_an_empty_buffer_still_runs_a_full_cycle() {
        TestingHarness h = harness(true);
        double hungerBefore = h.creature().emotions().getLevel(Constants.HUNGER);

        // Enough ticks to flush at least one metabolic batch (HOMEO_BATCH_SIZE).
        for (int i = 0; i < Constants.HOMEO_BATCH_SIZE + 1; i++) h.tick();

        EmotionalStimulus emotional = h.fullRecorder().lastOf(EmotionalStimulus.class);
        assertNotNull(emotional, "an empty sensory field must still produce a cognitive cycle");
        assertEquals(1, emotional.getPerceptions().size());
        assertInstanceOf(Self.class, emotional.getPerceptions().get(0).objectType.get(),
                "with nothing perceived, the Self fallback applies - and now genuinely means "
                        + "'nothing was there this window'");
        assertTrue(h.creature().emotions().getLevel(Constants.HUNGER) > hungerBefore,
                "metabolism must advance on perception-free ticks, or a creature alone in "
                        + "empty space would never grow hungry, act, or die");
    }

    // --- 6. the baseline arm's switch still works ---------------------------------------

    @Test
    void tick_gating_disabled_restores_per_delivery_cycling() {
        TestingHarness h = harness(false);

        h.injectLuminous(appleAt(h, 90_200L, 60));

        assertTrue(h.fullRecorder().hasAny(EmotionalStimulus.class),
                "with tickGatedCognition=false a delivery alone must run a cycle - this is "
                        + "the pre-#85 behaviour the p85 baseline arm measures against");
    }
}
