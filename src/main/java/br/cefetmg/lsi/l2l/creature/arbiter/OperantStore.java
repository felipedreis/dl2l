package br.cefetmg.lsi.l2l.creature.arbiter;

/**
 * A learning estimator that both answers the arbiter ({@link ValueEstimator}) and takes the values
 * that closed episodes write ({@link ValueLearner}). Implemented by {@link OperantEstimator}.
 *
 * <p>Held behind a TypedActor facade in {@code CreatureActor}, so that {@code Valuation} (the single
 * writer, through {@link OperantReturnSink}) and {@code FullAppraisal} (the reader) share one table
 * without sharing the object itself.
 */
public interface OperantStore extends ValueEstimator, ValueLearner {
}
