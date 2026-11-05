package br.cefetmg.lsi.l2l.cluster.settings;

import br.cefetmg.lsi.l2l.creature.arbiter.FocusTracker;
import br.cefetmg.lsi.l2l.creature.bd.ActionSelectionType;
import br.cefetmg.lsi.l2l.creature.conditioning.expectancy.ExpectancyMode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for LearningSettings parsing and flag semantics.
 * Covers the three configurable subsystems: circadian cycle,
 * memory consolidation, and action filter chain.
 */
public class LearningSettingsTest {

    // -----------------------------------------------------------------------
    // Default construction
    // -----------------------------------------------------------------------

    @Test
    void defaults_enable_circadian() {
        assertTrue(new LearningSettings().isCircadianEnabled());
    }

    @Test
    void defaults_enable_consolidation() {
        assertTrue(new LearningSettings().isConsolidationEnabled());
    }

    @Test
    void defaults_include_all_five_filters_in_master_order() {
        List<ActionSelectionType> filters = new LearningSettings().getEnabledFilters();
        assertEquals(5, filters.size());
        assertEquals(ActionSelectionType.TARGET_DISTANCE, filters.get(0));
        // MEMORY before AFFORDANCE: memory picks the object, the operant table picks the action
        // to take on it (Mapa 2009 §5.3.2), and the pair occupies Campos's single Memory slot.
        assertEquals(ActionSelectionType.MEMORY,          filters.get(1));
        assertEquals(ActionSelectionType.AFFORDANCE,      filters.get(2));
        assertEquals(ActionSelectionType.WORLD_MODEL,     filters.get(3));
        assertEquals(ActionSelectionType.RANDOM,          filters.get(4));
    }

    // -----------------------------------------------------------------------
    // Circadian toggle
    // -----------------------------------------------------------------------

    @Test
    void circadian_can_be_disabled() {
        LearningSettings ls = new LearningSettings(false, true, LearningSettings.MASTER_FILTER_ORDER);
        assertFalse(ls.isCircadianEnabled());
        assertTrue(ls.isConsolidationEnabled());
    }

    @Test
    void circadian_disabled_does_not_affect_consolidation_flag() {
        LearningSettings ls = new LearningSettings(false, true, LearningSettings.MASTER_FILTER_ORDER);
        assertTrue(ls.isConsolidationEnabled());
    }

    // -----------------------------------------------------------------------
    // Consolidation toggle
    // -----------------------------------------------------------------------

    @Test
    void consolidation_can_be_disabled() {
        LearningSettings ls = new LearningSettings(true, false, LearningSettings.MASTER_FILTER_ORDER);
        assertFalse(ls.isConsolidationEnabled());
        assertTrue(ls.isCircadianEnabled());
    }

    @Test
    void consolidation_disabled_does_not_affect_circadian_flag() {
        LearningSettings ls = new LearningSettings(true, false, LearningSettings.MASTER_FILTER_ORDER);
        assertTrue(ls.isCircadianEnabled());
    }

    // -----------------------------------------------------------------------
    // Filter list immutability
    // -----------------------------------------------------------------------

    @Test
    void enabled_filters_list_is_immutable() {
        LearningSettings ls = new LearningSettings();
        assertThrows(UnsupportedOperationException.class, () ->
                ls.getEnabledFilters().add(ActionSelectionType.RANDOM));
    }

    @Test
    void mutating_source_list_does_not_affect_stored_filters() {
        List<ActionSelectionType> mutable = new java.util.ArrayList<>();
        mutable.add(ActionSelectionType.RANDOM);
        LearningSettings ls = new LearningSettings(true, true, mutable);

        mutable.add(ActionSelectionType.AFFORDANCE); // mutate source after construction

        assertEquals(1, ls.getEnabledFilters().size(),
                "Stored filter list must not reflect post-construction mutations");
    }

    // -----------------------------------------------------------------------
    // Simulation config parsing via Typesafe Config
    // -----------------------------------------------------------------------

    @Test
    void simulation_parses_learning_settings_from_config() {
        String hocon = "simulation {\n"
                + "  holders = 1\n"
                + "  positionFactory = \"br.cefetmg.lsi.l2l.world.RandomPositionFactory\"\n"
                + "  reposition = false\n"
                + "  creatureSettings = [{quantity = 1}]\n"
                + "  worldObjectSettings = []\n"
                + "  worldSize = { width = 100, height = 100 }\n"
                + "  learningSettings {\n"
                + "    circadianEnabled    = false\n"
                + "    consolidationEnabled = false\n"
                + "    enabledFilters      = [TARGET_DISTANCE, RANDOM]\n"
                + "  }\n"
                + "}";

        com.typesafe.config.Config config = com.typesafe.config.ConfigFactory.parseString(hocon);
        Simulation sim = new Simulation(config);
        LearningSettings ls = sim.getLearningSettings();

        assertFalse(ls.isCircadianEnabled());
        assertFalse(ls.isConsolidationEnabled());
        assertEquals(List.of(ActionSelectionType.TARGET_DISTANCE, ActionSelectionType.RANDOM),
                ls.getEnabledFilters());
    }

    @Test
    void simulation_uses_defaults_when_learning_settings_block_is_absent() {
        String hocon = "simulation {\n"
                + "  holders = 1\n"
                + "  positionFactory = \"br.cefetmg.lsi.l2l.world.RandomPositionFactory\"\n"
                + "  reposition = false\n"
                + "  creatureSettings = [{quantity = 1}]\n"
                + "  worldObjectSettings = []\n"
                + "  worldSize = { width = 100, height = 100 }\n"
                + "}";

        com.typesafe.config.Config config = com.typesafe.config.ConfigFactory.parseString(hocon);
        Simulation sim = new Simulation(config);
        LearningSettings ls = sim.getLearningSettings();

        assertTrue(ls.isCircadianEnabled());
        assertTrue(ls.isConsolidationEnabled());
        assertEquals(LearningSettings.MASTER_FILTER_ORDER, ls.getEnabledFilters());
    }

    // -----------------------------------------------------------------------
    // Focal drive (issue #99)
    // -----------------------------------------------------------------------

    private static String simulationWith(String learningSettingsBody) {
        return "simulation {\n"
                + "  holders = 1\n"
                + "  positionFactory = \"br.cefetmg.lsi.l2l.world.RandomPositionFactory\"\n"
                + "  reposition = false\n"
                + "  creatureSettings = [{quantity = 1}]\n"
                + "  worldObjectSettings = []\n"
                + "  worldSize = { width = 100, height = 100 }\n"
                + "  learningSettings { " + learningSettingsBody + " }\n"
                + "}";
    }

    @Test
    void focal_drive_defaults_off_and_parses_from_config() {
        assertFalse(new LearningSettings().isFocalDriveEnabled());

        LearningSettings absent = new Simulation(com.typesafe.config.ConfigFactory
                .parseString(simulationWith("circadianEnabled = false"))).getLearningSettings();
        assertFalse(absent.isFocalDriveEnabled());

        LearningSettings on = new Simulation(com.typesafe.config.ConfigFactory
                .parseString(simulationWith("focalDriveEnabled = true"))).getLearningSettings();
        assertTrue(on.isFocalDriveEnabled());
    }

    @Test
    void operant_estimator_defaults_off_and_requires_the_focal_drive() {
        assertFalse(new LearningSettings().isOperantEstimatorEnabled());

        LearningSettings on = new Simulation(com.typesafe.config.ConfigFactory.parseString(
                simulationWith("focalDriveEnabled = true\noperantEstimatorEnabled = true"))).getLearningSettings();
        assertTrue(on.isOperantEstimatorEnabled());

        assertThrows(IllegalArgumentException.class, () -> new Simulation(com.typesafe.config.ConfigFactory
                .parseString(simulationWith("operantEstimatorEnabled = true"))).getLearningSettings());
    }

    @Test
    void focus_thresholds_default_to_the_tracker_defaults_and_parse_from_config() {
        FocusTracker.Params d = FocusTracker.Params.defaults();
        assertEquals(d.thetaOn(), new LearningSettings().getFocusThetaOn());
        assertEquals(d.thetaOff(), new LearningSettings().getFocusThetaOff());
        assertEquals(d.thetaOn(), parse("circadianEnabled = false").getFocusThetaOn());

        LearningSettings ls = parse("focalDriveEnabled = true\nfocusThetaOn = 0.5\nfocusThetaOff = 0.2");
        assertEquals(0.5, ls.getFocusThetaOn());
        assertEquals(0.2, ls.getFocusThetaOff());
    }

    @Test
    void focus_thresholds_must_keep_the_hysteresis() {
        assertThrows(IllegalArgumentException.class, () -> parse("focusThetaOn = 0.2\nfocusThetaOff = 0.2"));
        assertThrows(IllegalArgumentException.class, () -> parse("focusThetaOn = 0.2\nfocusThetaOff = 0.5"));
        assertThrows(IllegalArgumentException.class, () -> parse("focusThetaOn = 1.0\nfocusThetaOff = -0.1"));
    }

    private static LearningSettings parse(String body) {
        return new Simulation(com.typesafe.config.ConfigFactory.parseString(simulationWith(body))).getLearningSettings();
    }

    private static final String ARBITER_BASE =
            "focalDriveEnabled = true\noperantEstimatorEnabled = true\nactionSelector = ARBITER\n";

    @Test
    void action_selector_defaults_to_filter_based() {
        assertEquals(LearningSettings.ActionSelectorMode.FILTER_BASED, new LearningSettings().getActionSelector());
        assertFalse(new LearningSettings().isArbiterSelector());
        assertEquals(LearningSettings.ActionSelectorMode.FILTER_BASED, parse("circadianEnabled = false").getActionSelector());
    }

    @Test
    void arbiter_selector_parses_with_the_operant_estimator_and_target_distance_only() {
        LearningSettings a = parse(ARBITER_BASE + "enabledFilters = [TARGET_DISTANCE]");
        assertTrue(a.isArbiterSelector());
        assertEquals(LearningSettings.ActionSelectorMode.ARBITER, a.getActionSelector());
        assertEquals(List.of(ActionSelectionType.TARGET_DISTANCE), a.getEnabledFilters());
    }

    @Test
    void arbiter_selector_requires_the_operant_estimator() {
        assertThrows(IllegalArgumentException.class, () -> parse(
                "focalDriveEnabled = true\nactionSelector = ARBITER\nenabledFilters = [TARGET_DISTANCE]"));
    }

    @Test
    void arbiter_selector_rejects_every_legacy_filter_and_the_label_in_enabled_filters() {
        for (String filter : List.of("MEMORY", "AFFORDANCE", "WORLD_MODEL", "RANDOM", "ARBITER")) {
            assertThrows(IllegalArgumentException.class,
                    () -> parse(ARBITER_BASE + "enabledFilters = [TARGET_DISTANCE, " + filter + "]"), filter);
        }
    }

    @Test
    void arbiter_selector_rejects_neuromodulation() {
        assertThrows(IllegalArgumentException.class,
                () -> parse(ARBITER_BASE + "enabledFilters = [TARGET_DISTANCE]\nneuromodulationEnabled = true"));
    }

    @Test
    void a_null_selector_in_the_constructor_is_filter_based() {
        LearningSettings s = new LearningSettings(true, true, List.of(ActionSelectionType.RANDOM),
                false, ExpectancyMode.DISCRETE, false, false, false, false, true, false, false, null);
        assertEquals(LearningSettings.ActionSelectorMode.FILTER_BASED, s.getActionSelector());
    }

    @Test
    void p104_a2_check_conf_is_a0_with_focal_drive_operant_and_the_arbiter() {
        LearningSettings a2 = new Simulation(com.typesafe.config.ConfigFactory
                .parseFile(new java.io.File("simulations/p104_a2_check.conf"))).getLearningSettings();
        LearningSettings a0 = new Simulation(com.typesafe.config.ConfigFactory
                .parseFile(new java.io.File("simulations/arbiter_a0.conf"))).getLearningSettings();

        assertTrue(a2.isFocalDriveEnabled());
        assertTrue(a2.isOperantEstimatorEnabled());
        assertTrue(a2.isArbiterSelector());
        assertEquals(List.of(ActionSelectionType.TARGET_DISTANCE), a2.getEnabledFilters());
        assertEquals(a0.isActionTendencyEnabled(), a2.isActionTendencyEnabled());
        assertEquals(a0.isNeuromodulationEnabled(), a2.isNeuromodulationEnabled());
    }

    @Test
    void arbiter_a0_conf_is_the_p84_legacy_nomem_stack_with_focal_drive_off() {
        LearningSettings a0 = new Simulation(com.typesafe.config.ConfigFactory
                .parseFile(new java.io.File("simulations/arbiter_a0.conf"))).getLearningSettings();
        LearningSettings p84 = new Simulation(com.typesafe.config.ConfigFactory
                .parseFile(new java.io.File("simulations/p84_legacy_nomem.conf"))).getLearningSettings();

        assertFalse(a0.isFocalDriveEnabled());
        assertTrue(a0.isActionTendencyEnabled());
        assertFalse(a0.isNeuromodulationEnabled());
        assertFalse(a0.isExpectancyEnabled());
        assertFalse(a0.isOrexinEnabled());
        assertFalse(a0.isEndocrineEnabled());
        assertEquals(p84.getEnabledFilters(), a0.getEnabledFilters());
        assertEquals(p84.isCircadianEnabled(), a0.isCircadianEnabled());
        assertEquals(p84.isConsolidationEnabled(), a0.isConsolidationEnabled());
    }

    // -----------------------------------------------------------------------
    // Neuromodulatory expectancy loop (issue #57)
    // -----------------------------------------------------------------------

    @Test
    void defaults_disable_expectancy_and_neuromodulation() {
        LearningSettings ls = new LearningSettings();
        assertFalse(ls.isExpectancyEnabled());
        assertFalse(ls.isNeuromodulationEnabled());
        assertEquals(ExpectancyMode.DISCRETE, ls.getExpectancyMode());
    }

    @Test
    void three_arg_constructor_keeps_expectancy_off() {
        LearningSettings ls = new LearningSettings(true, false, LearningSettings.MASTER_FILTER_ORDER);
        assertFalse(ls.isExpectancyEnabled());
        assertFalse(ls.isNeuromodulationEnabled());
    }

    @Test
    void simulation_parses_expectancy_settings_from_config() {
        String hocon = "simulation {\n"
                + "  holders = 1\n"
                + "  positionFactory = \"br.cefetmg.lsi.l2l.world.RandomPositionFactory\"\n"
                + "  reposition = false\n"
                + "  creatureSettings = [{quantity = 1}]\n"
                + "  worldObjectSettings = []\n"
                + "  worldSize = { width = 100, height = 100 }\n"
                + "  learningSettings {\n"
                + "    expectancyEnabled      = true\n"
                + "    expectancyMode         = CONTINUOUS\n"
                + "    neuromodulationEnabled = true\n"
                + "  }\n"
                + "}";

        com.typesafe.config.Config config = com.typesafe.config.ConfigFactory.parseString(hocon);
        LearningSettings ls = new Simulation(config).getLearningSettings();

        assertTrue(ls.isExpectancyEnabled());
        assertEquals(ExpectancyMode.CONTINUOUS, ls.getExpectancyMode());
        assertTrue(ls.isNeuromodulationEnabled());
    }

    @Test
    void simulation_partial_overrides_only_specified_keys() {
        // Only circadianEnabled is overridden; consolidation and filters must use defaults.
        String hocon = "simulation {\n"
                + "  holders = 1\n"
                + "  positionFactory = \"br.cefetmg.lsi.l2l.world.RandomPositionFactory\"\n"
                + "  reposition = false\n"
                + "  creatureSettings = [{quantity = 1}]\n"
                + "  worldObjectSettings = []\n"
                + "  worldSize = { width = 100, height = 100 }\n"
                + "  learningSettings {\n"
                + "    circadianEnabled = false\n"
                + "  }\n"
                + "}";

        com.typesafe.config.Config config = com.typesafe.config.ConfigFactory.parseString(hocon);
        Simulation sim = new Simulation(config);
        LearningSettings ls = sim.getLearningSettings();

        assertFalse(ls.isCircadianEnabled());
        assertTrue(ls.isConsolidationEnabled(), "consolidation must default to true");
        assertEquals(LearningSettings.MASTER_FILTER_ORDER, ls.getEnabledFilters(),
                "filters must default to master order");
    }

    // -----------------------------------------------------------------------
    // Seed (issue #95)
    // -----------------------------------------------------------------------

    private static String hoconWithSeed(String seedLine) {
        return "simulation {\n"
                + "  holders = 1\n"
                + "  positionFactory = \"br.cefetmg.lsi.l2l.world.RandomPositionFactory\"\n"
                + "  reposition = false\n"
                + "  creatureSettings = [{quantity = 1}]\n"
                + "  worldObjectSettings = []\n"
                + "  worldSize = { width = 100, height = 100 }\n"
                + seedLine
                + "}";
    }

    @Test
    void simulation_reads_seed_from_config_and_builds_the_same_world_from_it() {
        Simulation a = new Simulation(com.typesafe.config.ConfigFactory.parseString(hoconWithSeed("  seed = 123\n")));
        Simulation b = new Simulation(com.typesafe.config.ConfigFactory.parseString(hoconWithSeed("  seed = 123\n")));
        Simulation c = new Simulation(com.typesafe.config.ConfigFactory.parseString(hoconWithSeed("  seed = 124\n")));

        assertEquals(123L, a.getSeed());
        assertFalse(a.isSeedGenerated());
        br.cefetmg.lsi.l2l.common.SequentialId id = new br.cefetmg.lsi.l2l.common.SequentialId(9);
        assertEquals(a.getPositionFactory().positionFor(id), b.getPositionFactory().positionFor(id));
        assertNotEquals(a.getPositionFactory().positionFor(id), c.getPositionFactory().positionFor(id));
    }

    @Test
    void simulation_without_seed_generates_one_and_says_so() {
        Simulation a = new Simulation(com.typesafe.config.ConfigFactory.parseString(hoconWithSeed("")));
        Simulation b = new Simulation(com.typesafe.config.ConfigFactory.parseString(hoconWithSeed("")));
        assertTrue(a.isSeedGenerated());
        assertNotEquals(a.getSeed(), b.getSeed());
    }
}
