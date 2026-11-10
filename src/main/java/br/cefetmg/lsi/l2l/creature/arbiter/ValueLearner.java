package br.cefetmg.lsi.l2l.creature.arbiter;

import br.cefetmg.lsi.l2l.creature.common.ActionType;
import br.cefetmg.lsi.l2l.world.WorldObjectType;

/**
 * Write side of a learning estimator: records the value experienced after an action on an object type.
 *
 * <p>{@code Valuation} calls {@link #record} once per outcome that reinforces the legacy operant table (the
 * {@code EvaluationStimulus} events on fruit and plant targets), with the realised drive reduction as the value.
 * Reads go through {@link ValueEstimator}; a concrete class may implement both, as {@link OperantEstimator} does.
 *
 * <p>Not thread-safe. The owner of a learner is a wiring decision, not part of this contract.
 */
public interface ValueLearner {

    /**
     * @param target the object type the action was performed on; {@code null} for an action without an object
     * @param action the action the value is credited to
     * @param value  value experienced; must be finite
     * @return the cell after the update
     * @throws IllegalArgumentException if {@code action} is null or {@code value} is NaN or infinite
     */
    OperantUpdate record(WorldObjectType target, ActionType action, double value);
}
