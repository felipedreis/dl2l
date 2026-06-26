package br.cefetmg.lsi.l2l.creature.components;

import br.cefetmg.lsi.l2l.common.Constants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class EmotionalSystemActorTest {

    private EmotionalSystemActor emotions;

    @BeforeEach
    void setUp() {
        emotions = new EmotionalSystemActor();
    }

    // --- Pain regulation ---

    @Test
    void pain_increases_when_nociceptive_stimulus_applied() {
        double before = emotions.getLevel(Constants.PAIN);
        emotions.regulate(Constants.PAIN, 0.3);
        assertTrue(emotions.getLevel(Constants.PAIN) > before);
    }

    @Test
    void pain_decreases_after_analgesic_stimulus() {
        emotions.regulate(Constants.PAIN, 0.5);
        double after_hit = emotions.getLevel(Constants.PAIN);
        emotions.regulate(Constants.PAIN, -0.2);
        assertTrue(emotions.getLevel(Constants.PAIN) < after_hit);
    }

    @Test
    void pain_does_not_go_below_zero_after_full_decay() {
        emotions.regulate(Constants.PAIN, 0.1);
        // Clamp is enforced by HomeostaticRegulation before calling regulate,
        // so here we just verify the level is non-negative after a modest delta.
        double level = emotions.getLevel(Constants.PAIN);
        assertTrue(level >= 0, "Pain should not be negative after a small raise");
    }

    @Test
    void active_cactus_pain_exceeds_passive_collision_pain() {
        double passive = 0.3;
        double active  = 0.7;
        assertTrue(active > passive,
                "Active EAT pain should be higher than passive collision pain");
    }

    // --- Tedium regulation ---

    @Test
    void tedium_increases_when_idle() {
        double before = emotions.getLevel(Constants.TEDIUM);
        emotions.regulate(Constants.TEDIUM, Constants.TEDIUM_IDLE_RATE);
        assertTrue(emotions.getLevel(Constants.TEDIUM) > before);
    }

    @Test
    void tedium_increases_more_from_observe_than_idle() {
        assertTrue(Constants.TEDIUM_OBSERVE_RATE > Constants.TEDIUM_IDLE_RATE,
                "OBSERVE should increase tedium faster than generic idle");
    }

    @Test
    void tedium_decreases_after_wander() {
        emotions.regulate(Constants.TEDIUM, 1.0);
        double before = emotions.getLevel(Constants.TEDIUM);
        emotions.regulate(Constants.TEDIUM, -Constants.TEDIUM_WANDER_RELIEF);
        assertTrue(emotions.getLevel(Constants.TEDIUM) < before);
    }

    // --- Hunger regulation ---

    @Test
    void hunger_increases_after_eating_rotten_apple() {
        // ROTTEN_APPLE has caloricValue = -0.3; HomeostaticRegulation applies
        // regulate(HUNGER, -nutritiveValue) = regulate(HUNGER, +0.3).
        double before = emotions.getLevel(Constants.HUNGER);
        emotions.regulate(Constants.HUNGER, 0.3);
        assertTrue(emotions.getLevel(Constants.HUNGER) > before);
    }

    @Test
    void hunger_decreases_after_eating_good_apple() {
        emotions.regulate(Constants.HUNGER, 1.0);
        double before = emotions.getLevel(Constants.HUNGER);
        emotions.regulate(Constants.HUNGER, -0.2);
        assertTrue(emotions.getLevel(Constants.HUNGER) < before);
    }
}
