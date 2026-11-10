package br.cefetmg.lsi.l2l.creature.arbiter;

import java.util.List;

/**
 * Consumer of the steps an {@link EpisodeBuffer} closes: the engram writer of #111 and the realised-value log of
 * #105. The operant table is not one of them: it learns per outcome (see {@link OperantEstimator}).
 *
 * <p>Called on the owner's thread, once per closed segment, with the steps in decision-cycle order.
 * Never called with an empty list.
 */
public interface ReturnSink {

    /**
     * @param steps closed steps of one segment, oldest first; unmodifiable
     */
    void onClosed(List<ClosedStep> steps);
}
