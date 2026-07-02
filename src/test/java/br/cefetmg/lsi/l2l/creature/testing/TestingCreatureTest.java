package br.cefetmg.lsi.l2l.creature.testing;

import br.cefetmg.lsi.l2l.cluster.settings.LearningSettings;
import br.cefetmg.lsi.l2l.common.Constants;
import br.cefetmg.lsi.l2l.common.Point;
import br.cefetmg.lsi.l2l.common.SequentialId;
import br.cefetmg.lsi.l2l.creature.bd.ActionSelectionType;
import br.cefetmg.lsi.l2l.creature.common.ActionType;
import br.cefetmg.lsi.l2l.stimuli.CorticalStimulus;
import br.cefetmg.lsi.l2l.stimuli.DestructiveStimulus;
import br.cefetmg.lsi.l2l.stimuli.EmotionalStimulus;
import br.cefetmg.lsi.l2l.stimuli.EnergeticStimulus;
import br.cefetmg.lsi.l2l.stimuli.LuminousStimulus;
import br.cefetmg.lsi.l2l.stimuli.MechanicalStimulus;
import br.cefetmg.lsi.l2l.stimuli.MuscularStimulus;
import br.cefetmg.lsi.l2l.stimuli.NociceptiveStimulus;
import br.cefetmg.lsi.l2l.stimuli.ProprioceptiveStimulus;
import br.cefetmg.lsi.l2l.stimuli.SomaticStimulus;
import br.cefetmg.lsi.l2l.stimuli.VisualStimulus;
import br.cefetmg.lsi.l2l.creature.ml.SleepStarted;
import br.cefetmg.lsi.l2l.creature.components.Mouth;
import br.cefetmg.lsi.l2l.world.FruitType;
import br.cefetmg.lsi.l2l.world.PlantType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Functional tests that drive a {@link TestingCreature} through full stimulus cycles
 * without any {@code ActorSystem}. The harness is single-threaded; each test
 * synchronously injects an external stimulus or ticks the cognitive clock, then asserts
 * on the recorded chain of internal stimuli and the messages emitted to the
 * (stubbed) external world.
 */
class TestingCreatureTest {

    // For most tests we want a single deterministic filter: TARGET_DISTANCE returns the
    // input list unless duplicates by (target, type) exist, so ActionSelection.selectOne
    // falls back to filtered.get(0) — which is the first action in the per-perception
    // priority list defined by FullAppraisal.actionsForPerception.
    private static LearningSettings deterministicSettings() {
        return new LearningSettings(true, false, List.of(ActionSelectionType.TARGET_DISTANCE));
    }

    private TestingHarness newHarness() {
        return TestingHarness.builder().learningSettings(deterministicSettings()).build();
    }

    // -------- 1. empty perception tick ----------------------------------------------------

    @Test
    void empty_tick_drives_partial_to_full_pipeline_and_chooses_a_rest_action() {
        TestingHarness h = newHarness();

        h.tick();

        // PartialAppraisal must have emitted an EmotionalStimulus to FullAppraisal.
        EmotionalStimulus emotional = h.fullRecorder().lastOf(EmotionalStimulus.class);
        assertNotNull(emotional, "PartialAppraisal should have emitted an EmotionalStimulus");
        assertFalse(emotional.getPerceptions().isEmpty(),
                "With no external input, PartialAppraisal should inject a Self perception");

        // FullAppraisal must have emitted a CorticalStimulus to EffectorCortex.
        CorticalStimulus cortical = h.effectorCortexRecorder().lastOf(CorticalStimulus.class);
        assertNotNull(cortical, "FullAppraisal should have emitted a CorticalStimulus");
        // With no real perception, the only available actions are SLEEP and WANDER.
        assertTrue(cortical.action == ActionType.SLEEP || cortical.action == ActionType.WANDER,
                "Expected SLEEP or WANDER for a Self-only perception, got " + cortical.action);
    }

    // -------- 2. see-and-approach ---------------------------------------------------------

    @Test
    void see_red_apple_at_distance_triggers_full_chain_and_approach() {
        TestingHarness h = newHarness();
        Point applePos = new Point(150, 100); // creature at (100,100), apple 50 units east

        LuminousStimulus luminous = new LuminousStimulus(
                new SequentialId(42L), new SequentialId(43L), FruitType.RED_APPLE, applePos);
        h.injectLuminous(luminous);

        // Chain: Eye->SensoryCortex->Partial->Full->EffectorCortex->Body
        assertTrue(h.sensoryCortexRecorder().hasAny(VisualStimulus.class),
                "Eye should have forwarded a VisualStimulus to SensoryCortex");
        assertTrue(h.partialRecorder().hasAny(ProprioceptiveStimulus.class),
                "SensoryCortex should have forwarded a ProprioceptiveStimulus to PartialAppraisal");
        assertTrue(h.fullRecorder().hasAny(EmotionalStimulus.class),
                "PartialAppraisal should have forwarded an EmotionalStimulus to FullAppraisal");
        CorticalStimulus cortical = h.effectorCortexRecorder().lastOf(CorticalStimulus.class);
        assertNotNull(cortical, "FullAppraisal should have produced a CorticalStimulus");
        assertEquals(ActionType.APPROACH, cortical.action,
                "An apple at distance should trigger APPROACH (first in actionsAtDistance)");
        assertTrue(h.bodyRecorder().hasAny(MuscularStimulus.class),
                "EffectorCortex should have driven the Body with a MuscularStimulus");

        // No food has been eaten — holder should NOT see a DestructiveStimulus.
        assertFalse(h.holderSink().hasAny(DestructiveStimulus.class),
                "Approaching at distance must not produce DestructiveStimulus");
    }

    // -------- 3. eat cycle ----------------------------------------------------------------

    @Test
    void touching_apple_triggers_eat_then_nutritive_reduces_hunger() {
        TestingHarness h = newHarness();

        // First raise hunger so the eat-cycle reduction is visible.
        h.creature().emotions().regulate(Constants.HUNGER, 1.0);
        double hungerBefore = h.creature().emotions().getLevel(Constants.HUNGER);

        // Contact with an apple: distance 0 → actionsAtContact → first action is EAT.
        // We synthesise a LuminousStimulus at the creature's position (distance 0).
        LuminousStimulus contact = new LuminousStimulus(
                new SequentialId(50L), new SequentialId(51L),
                FruitType.RED_APPLE, h.creature().getPosition());
        h.injectLuminous(contact);

        CorticalStimulus cortical = h.effectorCortexRecorder().lastOf(CorticalStimulus.class);
        assertNotNull(cortical);
        assertEquals(ActionType.EAT, cortical.action,
                "At contact, the deterministic filter picks EAT first");
        assertTrue(h.mouthRecorder().hasAny(SomaticStimulus.class),
                "EffectorCortex must drive the mouth with a SomaticStimulus");
        assertTrue(h.holderSink().hasAny(DestructiveStimulus.class),
                "Mouth must emit a DestructiveStimulus to the holder when EATing");

        // Now simulate the holder's response: the food sends an EnergeticStimulus back.
        EnergeticStimulus energetic = new EnergeticStimulus(
                new SequentialId(50L), new SequentialId(52L),
                FruitType.RED_APPLE.caloricValue, FruitType.RED_APPLE);
        h.inject(Mouth.class, energetic);

        double hungerAfter = h.creature().emotions().getLevel(Constants.HUNGER);
        assertTrue(hungerAfter < hungerBefore,
                "Eating a caloric apple must reduce hunger (before=" + hungerBefore + ", after=" + hungerAfter + ")");
    }

    // -------- 4. pain cycle ---------------------------------------------------------------

    @Test
    void cactus_collision_raises_pain_and_immune_response_decays_it() {
        TestingHarness h = newHarness();
        double painInitial = h.creature().emotions().getLevel(Constants.PAIN);
        assertEquals(Constants.MIN_AROUSAL_LEVEL, painInitial, 1e-9);

        MechanicalStimulus collision = new MechanicalStimulus(
                new SequentialId(60L), new SequentialId(61L), PlantType.CACTUS);
        h.injectMechanical(collision);

        // Mouth must have routed a NociceptiveStimulus to HomeostaticRegulation.
        assertTrue(h.homeostaticRecorder().hasAny(NociceptiveStimulus.class),
                "Mouth must emit a NociceptiveStimulus on cactus collision");

        // Pain was raised above the immune threshold by the passive pain (0.3),
        // then the immune response decayed it back toward the threshold.
        double painAfter = h.creature().emotions().getLevel(Constants.PAIN);
        assertTrue(painAfter > painInitial,
                "Pain must have risen above the initial level after the collision");
        assertTrue(painAfter <= Constants.PAIN_IMMUNE_THRESHOLD + Constants.PAIN_IMMUNE_RATE + 1e-9,
                "Pain should settle near the immune threshold (" + Constants.PAIN_IMMUNE_THRESHOLD
                        + "), got " + painAfter);
    }

    // -------- 5. sleep cycle --------------------------------------------------------------

    @Test
    void empty_perceptions_trigger_sleep_onset_signal_to_consolidator() {
        TestingHarness h = newHarness();

        h.tick();

        // With no perceptions and TARGET_DISTANCE-only filter, SLEEP is the first action
        // in the [SLEEP, WANDER] list for a Self perception → FullAppraisal selects SLEEP
        // and signals the memory consolidator.
        CorticalStimulus cortical = h.effectorCortexRecorder().lastOf(CorticalStimulus.class);
        assertNotNull(cortical);
        assertEquals(ActionType.SLEEP, cortical.action);

        assertTrue(h.memoryConsolidatorSink().hasAny(SleepStarted.class),
                "Sleep onset should signal the memory consolidator with SleepStarted");
    }

    @Test
    void sleep_state_is_sustained_across_subsequent_ticks_with_single_onset_signal() {
        TestingHarness h = newHarness();

        for (int i = 0; i < 5; i++) {
            h.tick();
        }

        // Every tick should produce a CorticalStimulus with SLEEP.
        List<CorticalStimulus> corticals = h.effectorCortexRecorder().ofType(CorticalStimulus.class);
        assertTrue(corticals.size() >= 5, "Expected at least 5 CorticalStimuli across 5 ticks");
        for (CorticalStimulus c : corticals) {
            assertEquals(ActionType.SLEEP, c.action,
                    "All ticks with no perception should select SLEEP");
        }

        // SleepStarted fires only on transition into sleep — exactly once across these ticks.
        List<SleepStarted> sleepStartedEvents = h.memoryConsolidatorSink().ofType(SleepStarted.class);
        assertEquals(1, sleepStartedEvents.size(),
                "SleepStarted should fire only on the first transition into SLEEP, not every tick");
    }

    // -------- 6. position update via APPROACH --------------------------------------------

    @Test
    void approach_action_moves_the_creature_position() {
        TestingHarness h = newHarness();
        Point start = h.creature().getPosition();

        LuminousStimulus luminous = new LuminousStimulus(
                new SequentialId(70L), new SequentialId(71L),
                FruitType.RED_APPLE, new Point(start.x + 100, start.y));
        h.injectLuminous(luminous);

        Point after = h.creature().getPosition();
        assertNotEquals(start, after,
                "Body should have moved when EffectorCortex commands a non-zero muscular speed");
    }

    // -------- 7. kill cycle ---------------------------------------------------------------

    // -------- 8. behavioural efficiency -------------------------------------------------

    @Test
    void behavioural_efficiency_is_nonzero_at_resting_arousal() {
        TestingHarness h = newHarness();

        h.tick();

        EmotionalStimulus emotional = h.fullRecorder().lastOf(EmotionalStimulus.class);
        assertNotNull(emotional);
        assertTrue(emotional.behaviouralEfficiency > 0,
                "Efficiency must be > 0 at MIN_AROUSAL_LEVEL; got " + emotional.behaviouralEfficiency);
    }

    @Test
    void behavioural_efficiency_scales_speed_and_focus_in_cortical_stimulus() {
        TestingHarness h = newHarness();

        // Raise hunger above MIN_AROUSAL so efficiency is meaningfully above 0.
        h.creature().emotions().regulate(Constants.HUNGER, 2.0);

        LuminousStimulus luminous = new LuminousStimulus(
                new SequentialId(80L), new SequentialId(81L),
                FruitType.RED_APPLE, new Point(200, 100));
        h.injectLuminous(luminous);

        CorticalStimulus cortical = h.effectorCortexRecorder().lastOf(CorticalStimulus.class);
        assertNotNull(cortical);
        // Speed and focus must be strictly above their minimums when efficiency > 0.
        assertTrue(cortical.speed > Constants.MIN_STEP,
                "Speed must exceed MIN_STEP when efficiency > 0; got " + cortical.speed);
        assertTrue(cortical.focus > Constants.MIN_VISION_FIELD_OPENING,
                "Focus must exceed MIN_VISION_FIELD_OPENING when efficiency > 0; got " + cortical.focus);
        // And must not exceed their maximums.
        assertTrue(cortical.speed <= Constants.MAX_STEP,
                "Speed must not exceed MAX_STEP; got " + cortical.speed);
        assertTrue(cortical.focus <= Constants.MAX_VISION_FIELD_OPENING,
                "Focus must not exceed MAX_VISION_FIELD_OPENING; got " + cortical.focus);
    }

    // -------- 9. kill cycle ---------------------------------------------------------------

    @Test
    void kill_notifies_holder_with_creature_id_and_marks_dead() {
        TestingHarness h = newHarness();
        SequentialId creatureId = h.creature().id();

        assertTrue(h.creature().isAlive());
        h.creature().setAlive(false);

        assertFalse(h.creature().isAlive());
        List<Object> holderMsgs = h.holderSink().messages();
        assertFalse(holderMsgs.isEmpty(),
                "Killing the creature must notify the holder");
        assertEquals(creatureId, holderMsgs.get(holderMsgs.size() - 1),
                "The kill notification carries the creature's SequentialId");
    }
}
