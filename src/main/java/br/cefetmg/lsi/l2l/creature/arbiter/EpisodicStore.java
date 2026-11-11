package br.cefetmg.lsi.l2l.creature.arbiter;

import java.util.List;

/**
 * The episodic controller as the rest of the creature sees it: answers the arbiter ({@link ValueEstimator}) and
 * takes the engrams that closed episodes write. Implemented by {@link EpisodicEstimator}.
 *
 * <p>Held behind a TypedActor facade in {@code CreatureActor}, like {@link OperantStore}: {@code Valuation} (the
 * writer) and {@code FullAppraisal} (the reader) share one store without sharing the object.
 */
public interface EpisodicStore extends ValueEstimator {

    /** Adds engrams, oldest first. Asynchronous through the facade (void). */
    void add(List<EpisodicEngram> engrams);

    /** Engrams held in all. */
    int size();
}
