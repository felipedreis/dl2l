package br.cefetmg.lsi.l2l.creature.arbiter;

import java.util.List;

/**
 * First {@link ReturnSink}: writes each closed step's value into the operant table, in the cell of
 * its focal drive, object type, distance bin and action (uncertainty arbitration paper, section 4.4).
 *
 * <p>The value is {@code V(G_t | H_t)}, plus the bootstrap term for a pre-empted step, as already
 * computed by {@link EpisodeBuffer}. Not thread-safe: called from the buffer's owner only.
 */
public final class OperantReturnSink implements ReturnSink {

    private final ValueLearner learner;

    /**
     * @param learner the table to write to
     * @throws IllegalArgumentException if {@code learner} is null
     */
    public OperantReturnSink(ValueLearner learner) {
        if (learner == null) {
            throw new IllegalArgumentException("learner must not be null");
        }
        this.learner = learner;
    }

    @Override
    public void onClosed(List<ClosedStep> steps) {
        for (ClosedStep step : steps) {
            if (!Double.isFinite(step.value())) {
                // record() rejects it; throwing inside the operant facade would restart that actor and
                // wipe the table, so one bad value costs one sample instead.
                continue;
            }
            learner.record(step.drive(), step.action(), step.value());
        }
    }
}
