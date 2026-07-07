package br.cefetmg.lsi.l2l.cluster.settings;

import br.cefetmg.lsi.l2l.common.Constants;
import br.cefetmg.lsi.l2l.creature.bd.ActionSelectionType;
import br.cefetmg.lsi.l2l.creature.common.ActionType;
import br.cefetmg.lsi.l2l.creature.conditioning.expectancy.ExpectancyMode;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Immutable snapshot of the learning subsystem feature flags loaded from
 * simulation.learningSettings in the simulation config file.
 * Serializable so it can be carried in cluster messages (e.g. CreateCreature).
 */
public class LearningSettings implements Serializable {

    /** Canonical priority order for all known action filters. */
    public static final List<ActionSelectionType> MASTER_FILTER_ORDER = List.of(
            ActionSelectionType.TARGET_DISTANCE,
            ActionSelectionType.AFFORDANCE,
            ActionSelectionType.MEMORY,
            ActionSelectionType.WORLD_MODEL,
            ActionSelectionType.RANDOM
    );

    /**
     * Campos (2006) innate action tendencies: the coarse action set that regulates each active drive.
     * Used by {@code ActionTendencyFilter} to bias action selection by the dominant emotion.
     */
    public static final Map<String, Set<ActionType>> DEFAULT_ACTION_TENDENCIES = Map.of(
            Constants.HUNGER, EnumSet.of(ActionType.EAT, ActionType.APPROACH, ActionType.WANDER),
            Constants.SLEEP,  EnumSet.of(ActionType.SLEEP, ActionType.WANDER),
            Constants.PAIN,   EnumSet.of(ActionType.ESCAPE, ActionType.AVOID, ActionType.WANDER),
            Constants.TEDIUM, EnumSet.of(ActionType.WANDER, ActionType.OBSERVE)
    );

    private final boolean circadianEnabled;
    private final boolean consolidationEnabled;
    private final List<ActionSelectionType> enabledFilters;

    // --- Neuromodulatory expectancy loop (issue #57) — default-off so the baseline is unchanged ---
    private final boolean expectancyEnabled;
    private final ExpectancyMode expectancyMode;
    private final boolean neuromodulationEnabled;

    // --- Innate emotion→action coupling (issue #57) — default-off ---
    private final boolean actionTendencyEnabled;

    public LearningSettings(boolean circadianEnabled,
                            boolean consolidationEnabled,
                            List<ActionSelectionType> enabledFilters) {
        this(circadianEnabled, consolidationEnabled, enabledFilters,
                false, ExpectancyMode.DISCRETE, false, false);
    }

    public LearningSettings(boolean circadianEnabled,
                            boolean consolidationEnabled,
                            List<ActionSelectionType> enabledFilters,
                            boolean expectancyEnabled,
                            ExpectancyMode expectancyMode,
                            boolean neuromodulationEnabled) {
        this(circadianEnabled, consolidationEnabled, enabledFilters,
                expectancyEnabled, expectancyMode, neuromodulationEnabled, false);
    }

    public LearningSettings(boolean circadianEnabled,
                            boolean consolidationEnabled,
                            List<ActionSelectionType> enabledFilters,
                            boolean expectancyEnabled,
                            ExpectancyMode expectancyMode,
                            boolean neuromodulationEnabled,
                            boolean actionTendencyEnabled) {
        this.circadianEnabled       = circadianEnabled;
        this.consolidationEnabled   = consolidationEnabled;
        this.enabledFilters         = Collections.unmodifiableList(new ArrayList<>(enabledFilters));
        this.expectancyEnabled      = expectancyEnabled;
        this.expectancyMode         = expectancyMode;
        this.neuromodulationEnabled = neuromodulationEnabled;
        this.actionTendencyEnabled  = actionTendencyEnabled;
    }

    /** Default: all subsystems enabled, full filter chain in canonical order. */
    public LearningSettings() {
        this(true, true, MASTER_FILTER_ORDER);
    }

    public boolean isCircadianEnabled() {
        return circadianEnabled;
    }

    /** Whether the expectancy → RPE → dopamine learning loop is active (else legacy binary valence). */
    public boolean isExpectancyEnabled() {
        return expectancyEnabled;
    }

    /** Which symbolic expectancy predictor to instantiate when {@link #isExpectancyEnabled()}. */
    public ExpectancyMode getExpectancyMode() {
        return expectancyMode;
    }

    /** Whether tonic dopamine/serotonin modulate action selection (exploration/patience). */
    public boolean isNeuromodulationEnabled() {
        return neuromodulationEnabled;
    }

    /** Whether the innate ActionTendency prior biases action selection by the dominant emotion. */
    public boolean isActionTendencyEnabled() {
        return actionTendencyEnabled;
    }

    /** The emotion→action tendency map used when {@link #isActionTendencyEnabled()}. */
    public Map<String, Set<ActionType>> getActionTendencies() {
        return DEFAULT_ACTION_TENDENCIES;
    }

    public boolean isConsolidationEnabled() {
        return consolidationEnabled;
    }

    /** Returns enabled filters in priority order (a subset of MASTER_FILTER_ORDER). */
    public List<ActionSelectionType> getEnabledFilters() {
        return enabledFilters;
    }

    public boolean isFilterEnabled(ActionSelectionType filterType) {
        return enabledFilters.contains(filterType);
    }
}
