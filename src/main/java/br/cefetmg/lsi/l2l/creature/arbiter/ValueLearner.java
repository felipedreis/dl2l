package br.cefetmg.lsi.l2l.creature.arbiter;

import br.cefetmg.lsi.l2l.creature.common.Action;

/**
 * Write side of a learning estimator: records the value experienced after a decision.
 *
 * <p>The episode close-out calls {@link #record} once per closed episode, with the drive that
 * held focus during it and the action it is credited to. Reads go through {@link ValueEstimator};
 * a concrete class may implement both, as {@link OperantEstimator} does.
 *
 * <p>Not thread-safe. The owner of a learner is a wiring decision, not part of this contract.
 */
public interface ValueLearner {

    /**
     * @param focalDrive drive that held focus during the episode, or {@code null} when none did
     * @param action     action the value is credited to
     * @param value      value experienced, {@code V(G | H)}; must be finite
     * @throws IllegalArgumentException if {@code action} is null or {@code value} is NaN or infinite
     */
    void record(String focalDrive, Action action, double value);
}
