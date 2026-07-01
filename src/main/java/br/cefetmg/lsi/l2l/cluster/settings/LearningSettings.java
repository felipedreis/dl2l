package br.cefetmg.lsi.l2l.cluster.settings;

import br.cefetmg.lsi.l2l.creature.bd.ActionSelectionType;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

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

    private final boolean circadianEnabled;
    private final boolean consolidationEnabled;
    private final List<ActionSelectionType> enabledFilters;

    public LearningSettings(boolean circadianEnabled,
                            boolean consolidationEnabled,
                            List<ActionSelectionType> enabledFilters) {
        this.circadianEnabled     = circadianEnabled;
        this.consolidationEnabled = consolidationEnabled;
        this.enabledFilters       = Collections.unmodifiableList(new ArrayList<>(enabledFilters));
    }

    /** Default: all subsystems enabled, full filter chain in canonical order. */
    public LearningSettings() {
        this(true, true, MASTER_FILTER_ORDER);
    }

    public boolean isCircadianEnabled() {
        return circadianEnabled;
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
