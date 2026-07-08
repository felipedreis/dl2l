package br.cefetmg.lsi.l2l.cluster.settings;

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
        assertEquals(ActionSelectionType.AFFORDANCE,      filters.get(1));
        assertEquals(ActionSelectionType.MEMORY,          filters.get(2));
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
}
