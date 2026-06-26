package br.cefetmg.lsi.l2l.creature.actionSelector;

import br.cefetmg.lsi.l2l.cluster.settings.LearningSettings;
import br.cefetmg.lsi.l2l.creature.bd.ActionSelectionType;
import br.cefetmg.lsi.l2l.creature.common.Action;
import br.cefetmg.lsi.l2l.creature.common.ActionType;
import br.cefetmg.lsi.l2l.creature.common.Perception;
import br.cefetmg.lsi.l2l.creature.components.Emotion;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests that the ActionSelection filter chain is built correctly from LearningSettings,
 * that priority order is preserved for enabled subsets, and that disabling individual
 * filters removes them from the chain without affecting the others.
 */
public class ActionSelectionConfigTest {

    private static final Emotion EMOTION = new Emotion("hunger");

    // Minimal Perception stub — only needs to exist, not carry meaningful data.
    private static final Perception SELF_PERCEPTION = new Perception(null, null, 0, 0);

    // Actions that a chain must reduce to one via RandomFilter.
    private List<Action> twoActions() {
        return new ArrayList<>(Arrays.asList(
                new Action(ActionType.WANDER, SELF_PERCEPTION),
                new Action(ActionType.SLEEP,  SELF_PERCEPTION)
        ));
    }

    // -----------------------------------------------------------------------
    // LearningSettings construction
    // -----------------------------------------------------------------------

    @Test
    void default_learning_settings_enables_all_filters_in_master_order() {
        LearningSettings ls = new LearningSettings();
        assertEquals(LearningSettings.MASTER_FILTER_ORDER, ls.getEnabledFilters());
        assertTrue(ls.isCircadianEnabled());
        assertTrue(ls.isConsolidationEnabled());
    }

    @Test
    void filter_enabled_check_returns_true_for_listed_filters() {
        LearningSettings ls = new LearningSettings(true, true,
                List.of(ActionSelectionType.TARGET_DISTANCE, ActionSelectionType.RANDOM));
        assertTrue(ls.isFilterEnabled(ActionSelectionType.TARGET_DISTANCE));
        assertTrue(ls.isFilterEnabled(ActionSelectionType.RANDOM));
        assertFalse(ls.isFilterEnabled(ActionSelectionType.AFFORDANCE));
        assertFalse(ls.isFilterEnabled(ActionSelectionType.WORLD_MODEL));
    }

    // -----------------------------------------------------------------------
    // ActionSelection with List<ActionFilter>
    // -----------------------------------------------------------------------

    @Test
    void action_selection_with_list_constructor_applies_filters_in_order() {
        // PassOneFilter always returns the first element → chain stops at this filter.
        ActionFilter passOne = new PassFirstFilter();

        ActionSelection sel = new ActionSelection(List.of(passOne));
        Action chosen = sel.selectOne(twoActions(), EMOTION);
        assertEquals(ActionType.WANDER, chosen.type, "First filter should pick first action");
    }

    @Test
    void random_only_chain_selects_one_from_many() {
        ActionSelection sel = new ActionSelection(List.of(new RandomFilter()));
        Action chosen = sel.selectOne(twoActions(), EMOTION);
        assertNotNull(chosen);
        assertTrue(chosen.type == ActionType.WANDER || chosen.type == ActionType.SLEEP);
    }

    @Test
    void empty_filter_list_leaves_actions_untouched_and_picks_first() {
        // With no filters the list is returned as-is; the caller picks index 0.
        ActionSelection sel = new ActionSelection(List.of());
        List<Action> actions = twoActions();
        Action chosen = sel.selectOne(actions, EMOTION);
        assertEquals(actions.get(0), chosen);
    }

    // -----------------------------------------------------------------------
    // Priority order is preserved for enabled subsets
    // -----------------------------------------------------------------------

    @Test
    void master_order_is_target_distance_affordance_world_model_random() {
        List<ActionSelectionType> expected = List.of(
                ActionSelectionType.TARGET_DISTANCE,
                ActionSelectionType.AFFORDANCE,
                ActionSelectionType.WORLD_MODEL,
                ActionSelectionType.RANDOM
        );
        assertEquals(expected, LearningSettings.MASTER_FILTER_ORDER);
    }

    @Test
    void enabling_subset_preserves_relative_order() {
        // Enable AFFORDANCE and RANDOM (skipping TARGET_DISTANCE and WORLD_MODEL).
        // The relative order of the enabled filters must still be AFFORDANCE < RANDOM.
        List<ActionSelectionType> subset = List.of(
                ActionSelectionType.AFFORDANCE,
                ActionSelectionType.RANDOM
        );
        LearningSettings ls = new LearningSettings(true, true, subset);

        // Build the chain using the same logic as FullAppraisal.preStart():
        List<ActionFilter> filterList = new ArrayList<>();
        for (ActionSelectionType type : LearningSettings.MASTER_FILTER_ORDER) {
            if (!ls.isFilterEnabled(type)) continue;
            if (type == ActionSelectionType.RANDOM) filterList.add(new RandomFilter());
            // AFFORDANCE would require OperantConditioning; use a stub that tracks insertion order.
            if (type == ActionSelectionType.AFFORDANCE) filterList.add(new OrderTrackingFilter("AFFORDANCE"));
        }

        // AFFORDANCE stub comes before RANDOM in the list → index 0 is AFFORDANCE.
        assertEquals(2, filterList.size());
        assertTrue(filterList.get(0) instanceof OrderTrackingFilter, "AFFORDANCE must be first");
        assertTrue(filterList.get(1) instanceof RandomFilter, "RANDOM must be second");
    }

    @Test
    void disabling_world_model_removes_it_from_chain() {
        LearningSettings ls = new LearningSettings(true, true,
                List.of(ActionSelectionType.TARGET_DISTANCE,
                        ActionSelectionType.AFFORDANCE,
                        ActionSelectionType.RANDOM));

        assertFalse(ls.isFilterEnabled(ActionSelectionType.WORLD_MODEL));

        // The filter list built using the master order must not contain WORLD_MODEL.
        List<ActionSelectionType> built = new ArrayList<>();
        for (ActionSelectionType type : LearningSettings.MASTER_FILTER_ORDER) {
            if (ls.isFilterEnabled(type)) built.add(type);
        }
        assertFalse(built.contains(ActionSelectionType.WORLD_MODEL));
        assertEquals(List.of(
                ActionSelectionType.TARGET_DISTANCE,
                ActionSelectionType.AFFORDANCE,
                ActionSelectionType.RANDOM), built);
    }

    @Test
    void disabling_all_filters_produces_empty_chain() {
        LearningSettings ls = new LearningSettings(true, true, List.of());
        for (ActionSelectionType type : LearningSettings.MASTER_FILTER_ORDER) {
            assertFalse(ls.isFilterEnabled(type));
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /** Always returns the single first action — used to test chain ordering. */
    private static class PassFirstFilter implements ActionFilter {
        @Override
        public List<Action> filter(List<Action> actions, Emotion toRegulate) {
            return List.of(actions.get(0));
        }

        @Override
        public ActionSelectionType getFilterType() { return ActionSelectionType.RANDOM; }
    }

    /** Pass-through stub that tracks insertion order without needing real dependencies. */
    private static class OrderTrackingFilter implements ActionFilter {
        private final String name;

        OrderTrackingFilter(String name) { this.name = name; }

        @Override
        public List<Action> filter(List<Action> actions, Emotion toRegulate) {
            return actions;
        }

        @Override
        public ActionSelectionType getFilterType() { return ActionSelectionType.AFFORDANCE; }
    }
}
