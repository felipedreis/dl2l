package br.cefetmg.lsi.l2l.creature.arbiter;

import br.cefetmg.lsi.l2l.creature.common.Action;

/**
 * One buffered decision, written out once its episode has closed (uncertainty arbitration paper,
 * section 4.2, Fig. 2). Immutable: the array components are copied on the way in and out.
 *
 * @param episodeId  id of the episode the step belongs to; a resumed episode keeps its id
 * @param drive      focal drive the step was decided under
 * @param closeType  how the segment holding the step was closed
 * @param cycle      decision cycle of the step ({@code t})
 * @param closeCycle cycle the segment was cut at: the close cycle, or the cut cycle for a pre-empted segment
 * @param action     action taken at {@code cycle}
 * @param h          drive levels at decision time, {@code H_t}, in {@link FocusTracker#DRIVES} order
 * @param g          discounted return {@code G_t} of the step, one entry per drive
 * @param bootstrap  {@code B} added to the value of a pre-empted step (the Q of the decision at the
 *                   cut cycle, {@code 0} when that Q is unknown); {@code NaN} for every other close type
 * @param value      {@code V(G_t | H_t)}, plus {@code gamma^(closeCycle - cycle) * B} when pre-empted
 */
public record ClosedStep(long episodeId, String drive, CloseType closeType, int cycle, int closeCycle,
                         Action action, double[] h, double[] g, double bootstrap, double value) {

    /** How the segment holding a step was closed. */
    public enum CloseType { REGULATED, PREEMPTED, TIMED_OUT, DEATH }

    public ClosedStep {
        if (drive == null || closeType == null || action == null || h == null || g == null) {
            throw new IllegalArgumentException("drive, closeType, action, h and g must not be null");
        }
        h = h.clone();
        g = g.clone();
    }

    @Override
    public double[] h() {
        return h.clone();
    }

    @Override
    public double[] g() {
        return g.clone();
    }
}
