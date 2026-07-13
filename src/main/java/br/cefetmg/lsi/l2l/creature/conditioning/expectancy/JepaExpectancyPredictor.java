package br.cefetmg.lsi.l2l.creature.conditioning.expectancy;

import br.cefetmg.lsi.l2l.creature.actionSelector.WorldModelFilter;
import br.cefetmg.lsi.l2l.creature.common.ActionType;
import br.cefetmg.lsi.l2l.world.WorldObjectType;

import java.util.OptionalDouble;
import java.util.concurrent.atomic.AtomicReference;

/**
 * JEPA-backed {@link ExpectancyPredictor}: uses the world-model emotion head's predicted
 * aversive cost as the RPE baseline so that dopamine fires when reality deviates from
 * what the world model predicted, rather than from the historical running mean.
 *
 * <p>The predicted cost for each scored action is cached by {@link WorldModelFilter} during
 * action selection. {@link #expected} reads that cache — zero extra inference per cycle.
 * On gated cycles (low arousal, OOD self-disable) or cache misses, returns 0.0 (neutral
 * prior), equivalent to a null baseline.
 *
 * <p>{@link #observe} is a no-op: the JEPA model is trained offline. The adapter fine-tuning
 * path (MemoryConsolidator) already receives the RPE-weighted engram via
 * {@code Valuation → memorySystem.reinforceWarmTraces(-rpe)}, so the adapter
 * automatically learns to correct for JEPA prediction errors during sleep.
 */
public class JepaExpectancyPredictor implements ExpectancyPredictor {

    // Filled lazily by FullAppraisal.preStart() after WorldModelFilter is created.
    private final AtomicReference<WorldModelFilter> filterRef;

    public JepaExpectancyPredictor(AtomicReference<WorldModelFilter> filterRef) {
        this.filterRef = filterRef;
    }

    @Override
    public double expected(ExpectancyContext ctx, WorldObjectType target, ActionType action) {
        WorldModelFilter filter = filterRef.get();
        if (filter == null) return 0.0;
        OptionalDouble cost = filter.getPredictedCost(action);
        // aversiveCost > 0 → costly; reward convention is positive = benefit → negate
        return cost.isPresent() ? -cost.getAsDouble() : 0.0;
    }

    @Override
    public void observe(ExpectancyContext ctx, WorldObjectType target, ActionType action, double reward) {
        // No-op: JEPA is offline. The adapter learns via MemoryConsolidator during sleep.
    }
}
