package br.cefetmg.lsi.l2l.cluster;

import br.cefetmg.lsi.l2l.cluster.settings.LearningSettings;
import br.cefetmg.lsi.l2l.common.SequentialId;
import br.cefetmg.lsi.l2l.creature.bd.ActionSelectionType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests per-creature LearningSettings overrides and fallback behaviour in
 * SimulationSettingsExtension.Impl.
 *
 * Impl is a plain Java object — no ActorSystem needed.
 */
class SimulationSettingsExtensionTest {

    private SimulationSettingsExtension.Impl ext;
    private LearningSettings globalSettings;

    @BeforeEach
    void setUp() {
        ext = new SimulationSettingsExtension.Impl();
        globalSettings = new LearningSettings(false, false,
                List.of(ActionSelectionType.RANDOM));
        ext.configure(globalSettings);
    }

    // -----------------------------------------------------------------------
    // Global settings
    // -----------------------------------------------------------------------

    @Test
    void learningSettings_no_arg_returns_global() {
        assertSame(globalSettings, ext.learningSettings());
    }

    @Test
    void learningSettings_unknown_creature_falls_back_to_global() {
        long unknownKey = 999L;
        assertSame(globalSettings, ext.learningSettings(unknownKey),
                "Unknown creature key must fall back to global settings");
    }

    // -----------------------------------------------------------------------
    // Per-creature override
    // -----------------------------------------------------------------------

    @Test
    void configure_per_creature_returns_that_override() {
        LearningSettings perCreature = new LearningSettings(true, true,
                LearningSettings.MASTER_FILTER_ORDER);
        ext.configure(42L, perCreature);

        assertSame(perCreature, ext.learningSettings(42L));
    }

    @Test
    void per_creature_override_does_not_affect_other_creatures() {
        LearningSettings creature1Settings = new LearningSettings(true, true,
                LearningSettings.MASTER_FILTER_ORDER);
        ext.configure(1L, creature1Settings);

        // Creature 2 has no override → should get global
        assertSame(globalSettings, ext.learningSettings(2L));
    }

    @Test
    void per_creature_override_does_not_change_global() {
        LearningSettings perCreature = new LearningSettings(true, true,
                LearningSettings.MASTER_FILTER_ORDER);
        ext.configure(7L, perCreature);

        assertSame(globalSettings, ext.learningSettings(),
                "Global settings must be unaffected by a per-creature configure");
    }

    @Test
    void multiple_creatures_each_get_their_own_settings() {
        LearningSettings s1 = new LearningSettings(true, false, LearningSettings.MASTER_FILTER_ORDER);
        LearningSettings s2 = new LearningSettings(false, true, List.of(ActionSelectionType.RANDOM));

        ext.configure(1L, s1);
        ext.configure(2L, s2);

        assertSame(s1, ext.learningSettings(1L));
        assertSame(s2, ext.learningSettings(2L));
    }

    // -----------------------------------------------------------------------
    // Release (called by CreatureActor.kill())
    // -----------------------------------------------------------------------

    @Test
    void release_removes_per_creature_override_reverts_to_global() {
        LearningSettings perCreature = new LearningSettings(true, true,
                LearningSettings.MASTER_FILTER_ORDER);
        ext.configure(10L, perCreature);
        ext.releaseCreatureSettings(10L);

        assertSame(globalSettings, ext.learningSettings(10L),
                "After release, creature must revert to global settings");
    }

    @Test
    void release_of_unknown_key_is_harmless() {
        assertDoesNotThrow(() -> ext.releaseCreatureSettings(999L));
    }

    @Test
    void release_does_not_affect_other_creatures() {
        LearningSettings s1 = new LearningSettings(true, true, LearningSettings.MASTER_FILTER_ORDER);
        LearningSettings s2 = new LearningSettings(false, false, List.of(ActionSelectionType.RANDOM));

        ext.configure(1L, s1);
        ext.configure(2L, s2);

        ext.releaseCreatureSettings(1L);

        assertSame(globalSettings, ext.learningSettings(1L), "Released creature must fall back to global");
        assertSame(s2,             ext.learningSettings(2L), "Other creature must keep its override");
    }

    // -----------------------------------------------------------------------
    // SequentialId key sharing — all component IDs of a creature share id.key
    // -----------------------------------------------------------------------

    @Test
    void component_ids_share_creature_key_so_all_see_same_settings() {
        SequentialId creatureId = new SequentialId(5L);
        SequentialId comp1 = creatureId.next();       // (key=5, seq=1)
        SequentialId comp2 = creatureId.next().next(); // (key=5, seq=2)

        LearningSettings perCreature = new LearningSettings(true, true,
                LearningSettings.MASTER_FILTER_ORDER);
        ext.configure(creatureId.key, perCreature);

        // All component IDs have the same key as the creature
        assertEquals(creatureId.key, comp1.key);
        assertEquals(creatureId.key, comp2.key);

        assertSame(perCreature, ext.learningSettings(comp1.key));
        assertSame(perCreature, ext.learningSettings(comp2.key));
    }

    // -----------------------------------------------------------------------
    // CreateCreature message — null vs explicit LearningSettings
    // -----------------------------------------------------------------------

    @Test
    void createCreature_single_arg_constructor_has_null_settings() {
        SequentialId id = new SequentialId(1L);
        CreateCreature msg = new CreateCreature(id);
        assertNull(msg.learningSettings(),
                "Convenience constructor must produce null (= use global) settings");
    }

    @Test
    void createCreature_two_arg_constructor_carries_explicit_settings() {
        SequentialId id = new SequentialId(2L);
        LearningSettings ls = new LearningSettings(false, false, List.of(ActionSelectionType.AFFORDANCE));
        CreateCreature msg = new CreateCreature(id, ls);
        assertSame(ls, msg.learningSettings());
    }
}
