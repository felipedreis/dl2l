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
import br.cefetmg.lsi.l2l.stimuli.VisualStimulus;
import br.cefetmg.lsi.l2l.world.FruitType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests the attention/focus regulation loop (issue #58 / roadmap Finding D):
 * - APPROACH narrows focus as distance shrinks
 * - SLEEP and EAT set focus to MIN (eye closed)
 * - Eye emits no VisualStimulus while field is at minimum
 */
class FocusRegulationTest {

    private static final double DELTA = 1e-9;

    private static LearningSettings tendencyOn() {
        return new LearningSettings(true, false,
                List.of(ActionSelectionType.AFFORDANCE, ActionSelectionType.RANDOM),
                false, ExpectancyMode.DISCRETE, false, true);
    }

    private static TestingHarness hungryCreature() {
        TestingHarness h = TestingHarness.builder().learningSettings(tendencyOn()).build();
        h.creature().emotions().regulate(Constants.HUNGER, 3.0);
        return h;
    }

    @Test
    void approach_focus_decreases_as_distance_shrinks() {
        Point base = new Point(100, 100);

        // D=120: wide field
        TestingHarness h1 = TestingHarness.builder()
                .position(base).learningSettings(tendencyOn()).build();
        h1.creature().emotions().regulate(Constants.HUNGER, 3.0);
        SequentialId a1 = new SequentialId(90_001L);
        h1.injectLuminous(new LuminousStimulus(a1, a1.next(), FruitType.RED_APPLE,
                new Point(base.x + 120, base.y)));
        CorticalStimulus c1 = h1.effectorCortexRecorder().lastOf(CorticalStimulus.class);
        assertNotNull(c1);
        assertEquals(ActionType.APPROACH, c1.action);

        // D=30: narrow field
        TestingHarness h2 = TestingHarness.builder()
                .position(base).learningSettings(tendencyOn()).build();
        h2.creature().emotions().regulate(Constants.HUNGER, 3.0);
        SequentialId a2 = new SequentialId(90_001L);
        h2.injectLuminous(new LuminousStimulus(a2, a2.next(), FruitType.RED_APPLE,
                new Point(base.x + 30, base.y)));
        CorticalStimulus c2 = h2.effectorCortexRecorder().lastOf(CorticalStimulus.class);
        assertNotNull(c2);
        assertEquals(ActionType.APPROACH, c2.action);

        double expectedFocus120 = Constants.MIN_VISION_FIELD_OPENING
                + (Constants.MAX_VISION_FIELD_OPENING - Constants.MIN_VISION_FIELD_OPENING)
                * (120.0 / Constants.DEFAULT_VISION_FIELD_RADIUS);
        double expectedFocus30 = Constants.MIN_VISION_FIELD_OPENING
                + (Constants.MAX_VISION_FIELD_OPENING - Constants.MIN_VISION_FIELD_OPENING)
                * (30.0 / Constants.DEFAULT_VISION_FIELD_RADIUS);

        assertEquals(expectedFocus120, c1.focus, DELTA, "focus at D=120 should be " + expectedFocus120);
        assertEquals(expectedFocus30, c2.focus, DELTA, "focus at D=30 should be " + expectedFocus30);
        assertTrue(c1.focus > c2.focus, "focus must be wider at greater distance (monotonically decreasing)");
    }

    @Test
    void eat_focus_is_minimal() {
        TestingHarness h = hungryCreature();
        Point p = h.creature().getPosition();
        // distance=0: actionsAtContact → EAT is selected deterministically with tendency on
        SequentialId a = new SequentialId(90_001L);
        h.injectLuminous(new LuminousStimulus(a, a.next(), FruitType.RED_APPLE,
                new Point(p.x, p.y)));

        CorticalStimulus c = h.effectorCortexRecorder().lastOf(CorticalStimulus.class);
        assertNotNull(c);
        assertEquals(ActionType.EAT, c.action, "hungry creature at contact should EAT");
        assertEquals(Constants.MIN_VISION_FIELD_OPENING, c.focus, DELTA,
                "EAT must lock focus to MIN (contact)");
    }

    @Test
    void sleep_focus_is_minimal() {
        TestingHarness h = TestingHarness.builder()
                .learningSettings(tendencyOn()).build();
        h.creature().emotions().regulate(Constants.SLEEP, 5.0);

        // Run enough ticks to encounter at least one SLEEP action; anti-micro-nap keeps
        // SLEEP for ≥ MIN_SLEEP_TICKS consecutive cycles once it starts.
        for (int i = 0; i < 50; i++) h.tick();

        List<CorticalStimulus> sleepCorticals = h.effectorCortexRecorder()
                .ofType(CorticalStimulus.class).stream()
                .filter(c -> c.action == ActionType.SLEEP)
                .toList();

        assertFalse(sleepCorticals.isEmpty(),
                "SLEEP must be selected at least once in 50 ticks with high sleep arousal");
        for (CorticalStimulus c : sleepCorticals) {
            assertEquals(0.0, c.focus, DELTA,
                    "every SLEEP CorticalStimulus must have focus == 0.0 (eye literally closed)");
        }
    }

    @Test
    void eye_emits_no_visual_stimulus_when_field_is_closed() {
        TestingHarness h = TestingHarness.builder().build();
        // Directly set the field to 0.0 (simulating SLEEP FocusStimulus having been applied).
        h.creature().setVisionFieldOpening(0.0);

        h.sensoryCortexRecorder().clear();

        SequentialId a = new SequentialId(90_001L);
        h.injectLuminous(new LuminousStimulus(a, a.next(), FruitType.RED_APPLE,
                new Point(h.creature().getPosition().x + 50, h.creature().getPosition().y)));

        List<VisualStimulus> seen = h.sensoryCortexRecorder().ofType(VisualStimulus.class);
        assertTrue(seen.isEmpty(),
                "Eye must emit no VisualStimulus when visionFieldOpening is 0.0 (eye literally closed)");
    }

    @Test
    void wander_retains_arousal_scaled_wide_focus() {
        TestingHarness h = TestingHarness.builder()
                .learningSettings(tendencyOn()).build();
        // Hunger dominant → tendency filter with no objects → only WANDER passes through
        // (hunger tendency {EAT,APPROACH,WANDER} ∩ {SLEEP,WANDER,OBSERVE} = {WANDER})
        h.creature().emotions().regulate(Constants.HUNGER, 3.0);

        h.tick(); // no objects → WANDER selected

        CorticalStimulus c = h.effectorCortexRecorder().lastOf(CorticalStimulus.class);
        assertNotNull(c);
        assertEquals(ActionType.WANDER, c.action,
                "hungry creature with no objects should WANDER with tendency on");
        assertTrue(c.focus > Constants.MIN_VISION_FIELD_OPENING,
                "WANDER must keep arousal-scaled focus wider than MIN; got " + c.focus);
    }
}
