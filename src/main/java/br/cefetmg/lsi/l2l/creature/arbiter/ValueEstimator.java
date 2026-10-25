package br.cefetmg.lsi.l2l.creature.arbiter;

import br.cefetmg.lsi.l2l.creature.common.Action;

import java.util.List;

/**
 * Source of per-candidate {@link Estimate}s for the arbiter (uncertainty arbitration paper,
 * section 4.5: {@code ctrl.estimate(candidates, H)}).
 *
 * <p>Estimate-only by design: the arbiter reads beliefs and never teaches them. Learning is a
 * concrete estimator's own concern and is not part of this contract.
 *
 * <p>Implementations must be read-only: calling {@code estimate} never changes estimator state.
 * An estimator is per-creature state owned by its actor, not a shared instance.
 */
public interface ValueEstimator {

    /**
     * @param candidates candidate actions to evaluate
     * @param h          drive levels, in {@link FocusTracker#DRIVES} order
     * @return one {@link Estimate} per candidate, in the same order and of the same size as
     *         {@code candidates}; empty when {@code candidates} is empty
     */
    List<Estimate> estimate(List<Action> candidates, double[] h);
}
