package br.cefetmg.lsi.l2l.cluster.settings;

import br.cefetmg.lsi.l2l.creature.arbiter.FocusTracker;
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

    /**
     * Canonical priority order for all known action filters, following Campos et al. (2015)
     * Algorithm 1: {@code Affordances → ∩ActionTendency → excludeIdenticalTargets → Memory → Random}.
     *
     * <p>The mapping is not one-to-one with our filter names. His "Affordances" step is the
     * candidate <em>generator</em> — our {@code FullAppraisal.definePossibleActions} — not our
     * AFFORDANCE filter, which is an operant-conditioning table; in his design the operant
     * mechanism lives <em>inside</em> the Memory step ("LTM is the operant conditioning
     * mechanism"). We keep the two separate so memory's contribution stays measurable
     * (see {@code MemoryDecisionState}), which means his single Memory slot is occupied here by
     * the MEMORY → AFFORDANCE pair: memory picks the object, the operant table picks the action,
     * which is also Mapa's (2009) §5.3.2 division of labour.
     *
     * <p>TARGET_DISTANCE (his {@code excludeIdenticalTargets}) must stay ahead of MEMORY: it keeps
     * only the nearest instance of each (object type, action type), so by the time memory scores
     * per object type there is no ambiguity about which instance it meant.
     *
     * <p>{@code ActionTendencyFilter} is prepended separately in {@code FullAppraisal.preStart},
     * gated by its own flag; WORLD_MODEL has no counterpart in either source architecture.
     */
    public static final List<ActionSelectionType> MASTER_FILTER_ORDER = List.of(
            ActionSelectionType.TARGET_DISTANCE,
            ActionSelectionType.MEMORY,
            ActionSelectionType.AFFORDANCE,
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
            // Boredom drives exploration (WANDER for novelty), not passive staring (OBSERVE): a
            // content-but-bored creature explores rather than fixating on whatever is in view.
            Constants.TEDIUM, EnumSet.of(ActionType.WANDER)
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

    // --- Orexin wakefulness gate + cortisol/endocrine system (post-#57) — default-off ---
    private final boolean orexinEnabled;
    private final boolean endocrineEnabled;

    // --- Tick-gated cognition (issue #85) — default-ON, unlike every other flag here ---
    private final boolean tickGatedCognition;

    // --- Focal drive (issue #99) — default-off: filters see the per-cycle most-aroused emotion ---
    private final boolean focalDriveEnabled;

    // --- Operant estimator behind the episode close-out (issue #110) — default-off; needs focalDriveEnabled ---
    private final boolean operantEstimatorEnabled;

    // --- Focus tracker thresholds (follow-up to the p107 gate; docs/plans/focus-tracker-theta-config.md) ---
    // Defaults are FocusTracker.Params.defaults(), so every existing configuration is unchanged.
    private final double focusThetaOn;
    private final double focusThetaOff;

    /**
     * Which mechanism chooses the action (issue #104). {@code FILTER_BASED} is the legacy filter chain;
     * {@code ARBITER} is the uncertainty arbiter over the operant estimator.
     */
    public enum ActionSelectorMode { FILTER_BASED, ARBITER }

    private final ActionSelectorMode actionSelector;

    public LearningSettings(boolean circadianEnabled,
                            boolean consolidationEnabled,
                            List<ActionSelectionType> enabledFilters) {
        this(circadianEnabled, consolidationEnabled, enabledFilters,
                false, ExpectancyMode.DISCRETE, false, false, false, false);
    }

    public LearningSettings(boolean circadianEnabled,
                            boolean consolidationEnabled,
                            List<ActionSelectionType> enabledFilters,
                            boolean expectancyEnabled,
                            ExpectancyMode expectancyMode,
                            boolean neuromodulationEnabled) {
        this(circadianEnabled, consolidationEnabled, enabledFilters,
                expectancyEnabled, expectancyMode, neuromodulationEnabled, false, false, false);
    }

    public LearningSettings(boolean circadianEnabled,
                            boolean consolidationEnabled,
                            List<ActionSelectionType> enabledFilters,
                            boolean expectancyEnabled,
                            ExpectancyMode expectancyMode,
                            boolean neuromodulationEnabled,
                            boolean actionTendencyEnabled) {
        this(circadianEnabled, consolidationEnabled, enabledFilters,
                expectancyEnabled, expectancyMode, neuromodulationEnabled, actionTendencyEnabled,
                false, false);
    }

    public LearningSettings(boolean circadianEnabled,
                            boolean consolidationEnabled,
                            List<ActionSelectionType> enabledFilters,
                            boolean expectancyEnabled,
                            ExpectancyMode expectancyMode,
                            boolean neuromodulationEnabled,
                            boolean actionTendencyEnabled,
                            boolean orexinEnabled,
                            boolean endocrineEnabled) {
        this(circadianEnabled, consolidationEnabled, enabledFilters, expectancyEnabled,
                expectancyMode, neuromodulationEnabled, actionTendencyEnabled, orexinEnabled,
                endocrineEnabled, true);
    }

    public LearningSettings(boolean circadianEnabled,
                            boolean consolidationEnabled,
                            List<ActionSelectionType> enabledFilters,
                            boolean expectancyEnabled,
                            ExpectancyMode expectancyMode,
                            boolean neuromodulationEnabled,
                            boolean actionTendencyEnabled,
                            boolean orexinEnabled,
                            boolean endocrineEnabled,
                            boolean tickGatedCognition) {
        this(circadianEnabled, consolidationEnabled, enabledFilters, expectancyEnabled,
                expectancyMode, neuromodulationEnabled, actionTendencyEnabled, orexinEnabled,
                endocrineEnabled, tickGatedCognition, false);
    }

    public LearningSettings(boolean circadianEnabled,
                            boolean consolidationEnabled,
                            List<ActionSelectionType> enabledFilters,
                            boolean expectancyEnabled,
                            ExpectancyMode expectancyMode,
                            boolean neuromodulationEnabled,
                            boolean actionTendencyEnabled,
                            boolean orexinEnabled,
                            boolean endocrineEnabled,
                            boolean tickGatedCognition,
                            boolean focalDriveEnabled) {
        this(circadianEnabled, consolidationEnabled, enabledFilters, expectancyEnabled,
                expectancyMode, neuromodulationEnabled, actionTendencyEnabled, orexinEnabled,
                endocrineEnabled, tickGatedCognition, focalDriveEnabled, false);
    }

    public LearningSettings(boolean circadianEnabled,
                            boolean consolidationEnabled,
                            List<ActionSelectionType> enabledFilters,
                            boolean expectancyEnabled,
                            ExpectancyMode expectancyMode,
                            boolean neuromodulationEnabled,
                            boolean actionTendencyEnabled,
                            boolean orexinEnabled,
                            boolean endocrineEnabled,
                            boolean tickGatedCognition,
                            boolean focalDriveEnabled,
                            boolean operantEstimatorEnabled) {
        this(circadianEnabled, consolidationEnabled, enabledFilters, expectancyEnabled, expectancyMode,
                neuromodulationEnabled, actionTendencyEnabled, orexinEnabled, endocrineEnabled, tickGatedCognition,
                focalDriveEnabled, operantEstimatorEnabled, ActionSelectorMode.FILTER_BASED);
    }

    /**
     * @throws IllegalArgumentException if {@code operantEstimatorEnabled} is set without
     *                                  {@code focalDriveEnabled}: the estimator is only fed by episodes,
     *                                  and episodes exist only under the focal drive; or if {@code actionSelector}
     *                                  is {@code ARBITER} and any of these holds: the operant estimator is off
     *                                  (the arbiter's only controller reads it); {@code enabledFilters} holds a
     *                                  filter other than {@code TARGET_DISTANCE} (the arbiter replaces the rest);
     *                                  neuromodulation is on (tau is a constant, see #104 Q7)
     */
    public LearningSettings(boolean circadianEnabled,
                            boolean consolidationEnabled,
                            List<ActionSelectionType> enabledFilters,
                            boolean expectancyEnabled,
                            ExpectancyMode expectancyMode,
                            boolean neuromodulationEnabled,
                            boolean actionTendencyEnabled,
                            boolean orexinEnabled,
                            boolean endocrineEnabled,
                            boolean tickGatedCognition,
                            boolean focalDriveEnabled,
                            boolean operantEstimatorEnabled,
                            ActionSelectorMode actionSelector) {
        this(circadianEnabled, consolidationEnabled, enabledFilters, expectancyEnabled, expectancyMode,
                neuromodulationEnabled, actionTendencyEnabled, orexinEnabled, endocrineEnabled, tickGatedCognition,
                focalDriveEnabled, operantEstimatorEnabled, actionSelector,
                FocusTracker.Params.defaults().thetaOn(), FocusTracker.Params.defaults().thetaOff());
    }

    /**
     * @param focusThetaOn  urgency at which a drive opens an episode ({@code FocusTracker.Params.thetaOn})
     * @param focusThetaOff urgency at or below which the focal episode closes as regulated
     * @throws IllegalArgumentException additionally if {@code 0 <= focusThetaOff < focusThetaOn} does not hold
     *                                  (hysteresis: an episode must not close the cycle it opens)
     */
    public LearningSettings(boolean circadianEnabled,
                            boolean consolidationEnabled,
                            List<ActionSelectionType> enabledFilters,
                            boolean expectancyEnabled,
                            ExpectancyMode expectancyMode,
                            boolean neuromodulationEnabled,
                            boolean actionTendencyEnabled,
                            boolean orexinEnabled,
                            boolean endocrineEnabled,
                            boolean tickGatedCognition,
                            boolean focalDriveEnabled,
                            boolean operantEstimatorEnabled,
                            ActionSelectorMode actionSelector,
                            double focusThetaOn,
                            double focusThetaOff) {
        if (!(focusThetaOff >= 0 && focusThetaOff < focusThetaOn)) {
            throw new IllegalArgumentException("focus thresholds need 0 <= thetaOff < thetaOn, got thetaOn="
                    + focusThetaOn + ", thetaOff=" + focusThetaOff);
        }
        this.focusThetaOn  = focusThetaOn;
        this.focusThetaOff = focusThetaOff;
        if (operantEstimatorEnabled && !focalDriveEnabled) {
            throw new IllegalArgumentException("operantEstimatorEnabled requires focalDriveEnabled");
        }
        ActionSelectorMode mode = actionSelector == null ? ActionSelectorMode.FILTER_BASED : actionSelector;
        if (mode == ActionSelectorMode.ARBITER) {
            if (!operantEstimatorEnabled) {
                throw new IllegalArgumentException("actionSelector = ARBITER requires operantEstimatorEnabled");
            }
            for (ActionSelectionType type : enabledFilters) {
                if (type != ActionSelectionType.TARGET_DISTANCE) {
                    throw new IllegalArgumentException("actionSelector = ARBITER replaces the filter " + type
                            + "; only TARGET_DISTANCE may be enabled");
                }
            }
            if (neuromodulationEnabled) {
                throw new IllegalArgumentException("actionSelector = ARBITER requires neuromodulationEnabled = false");
            }
        }
        this.actionSelector = mode;
        this.circadianEnabled       = circadianEnabled;
        this.consolidationEnabled   = consolidationEnabled;
        this.enabledFilters         = Collections.unmodifiableList(new ArrayList<>(enabledFilters));
        this.expectancyEnabled      = expectancyEnabled;
        this.expectancyMode         = expectancyMode;
        this.neuromodulationEnabled = neuromodulationEnabled;
        this.actionTendencyEnabled  = actionTendencyEnabled;
        this.orexinEnabled          = orexinEnabled;
        this.endocrineEnabled       = endocrineEnabled;
        this.tickGatedCognition     = tickGatedCognition;
        this.focalDriveEnabled      = focalDriveEnabled;
        this.operantEstimatorEnabled = operantEstimatorEnabled;
    }

    /** Urgency at which a drive opens an episode (default 2.0). Only read when {@link #isFocalDriveEnabled()}. */
    public double getFocusThetaOn() {
        return focusThetaOn;
    }

    /** Urgency at or below which the focal episode closes as regulated (default 0.5). */
    public double getFocusThetaOff() {
        return focusThetaOff;
    }

    /**
     * Issue #104: which mechanism chooses the action. {@code FILTER_BASED} unless configured otherwise, so the
     * legacy behaviour is unchanged by default.
     */
    public ActionSelectorMode getActionSelector() {
        return actionSelector;
    }

    /** Whether the arbiter chooses the action (issue #104). */
    public boolean isArbiterSelector() {
        return actionSelector == ActionSelectorMode.ARBITER;
    }

    /** Default: all subsystems enabled, full filter chain in canonical order. */
    public LearningSettings() {
        this(true, true, MASTER_FILTER_ORDER);
    }

    public boolean isCircadianEnabled() {
        return circadianEnabled;
    }

    /**
     * Issue #85: whether a cognitive cycle runs once per wall-clock tick over the perception
     * buffered since the previous tick (true, the default and the only supported production
     * setting), or once per message delivery over that delivery's own stimuli (false).
     *
     * <p>False reproduces the pre-#85 behaviour - cycle rate ~9x
     * {@link Constants#TARGET_CYCLE_HZ} and perception alternating with empty heartbeat
     * cycles at ~66 Hz. It exists so the p85 experiment's baseline arm can run from the same
     * build as the fixed arm, keeping the new instrumentation column present in both.
     */
    public boolean isTickGatedCognition() {
        return tickGatedCognition;
    }

    /**
     * Issue #99: whether the emotion passed to the selection filters is the
     * {@code FocusTracker}'s focal drive instead of the per-cycle most-aroused one. While no
     * episode is open the most-aroused emotion is still used, so calm-state behaviour is A0's.
     */
    public boolean isFocalDriveEnabled() {
        return focalDriveEnabled;
    }

    /**
     * Issue #110: whether the operant estimator is created behind a TypedActor facade and fed the
     * values of closed episodes. Always false unless {@link #isFocalDriveEnabled()}.
     */
    public boolean isOperantEstimatorEnabled() {
        return operantEstimatorEnabled;
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

    /**
     * Whether the neuromodulator loop is running at all (expectancy produces dopamine, and/or
     * neuromodulation reads tonic levels). When active, the reward-absence tedium model regulates
     * tedium and the legacy action-based tedium drift is suppressed.
     */
    public boolean isNeuromodulatorLoopActive() {
        return expectancyEnabled || neuromodulationEnabled;
    }

    /** Whether the innate ActionTendency prior biases action selection by the dominant emotion. */
    public boolean isActionTendencyEnabled() {
        return actionTendencyEnabled;
    }

    /** Whether the orexin wakefulness gate is active (gates SLEEP out when creature is rested). */
    public boolean isOrexinEnabled() {
        return orexinEnabled;
    }

    /** Whether the cortisol/endocrine system (HPA axis) is active. */
    public boolean isEndocrineEnabled() {
        return endocrineEnabled;
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
