package br.cefetmg.lsi.l2l.creature.arbiter;

import br.cefetmg.lsi.l2l.creature.common.ActionType;

/**
 * One episodic engram (uncertainty arbitration paper, sections 4.2-4.3, issue #111): a closed episode step as
 * the episodic controller remembers it. Born when the step's episode closes, never changed afterwards.
 *
 * <p>Not the legacy {@code memory.Engram}: that one is a reinforced trace ({@code emotionDelta}, eligibility) read
 * by {@code MemoryFilter} and the consolidators; this one carries the return vector {@code G} and a retrieval key
 * (docs/plans/issue-111-episodic-engram.md).
 *
 * <p>The value at retrieval is {@code V(G | H_now) + bootstrapTerm}: {@code G} is valued against the creature's
 * current drives, so the same memory answers any future need, and a pre-empted step adds its bootstrapped tail
 * (already a value, not a drive vector).
 *
 * @param action          the action the step took
 * @param objectType      name of the object acted on; {@code null} for none
 * @param key             retrieval key {@code k}, {@link EngramKeyEncoder#KEY_DIM} entries (copied)
 * @param h               drive levels at decision time, in {@link FocusTracker#DRIVES} order (copied)
 * @param g               discounted return {@code G}, one entry per drive (copied)
 * @param bootstrapTerm   {@code gamma^(T-t) * B} for a pre-empted step, 0 otherwise
 * @param episodeId       id of the episode the step belongs to (restarts for every creature)
 * @param drive           focal drive the step was decided under
 * @param closeType       how the step's segment closed
 * @param stepCycle       decision cycle of the step
 * @param stepsToOutcome  cycles from the step to the close of its segment
 */
public record EpisodicEngram(ActionType action, String objectType, double[] key, double[] h, double[] g,
                             double bootstrapTerm, long episodeId, String drive,
                             ClosedStep.CloseType closeType, int stepCycle, int stepsToOutcome) {

    public EpisodicEngram {
        if (action == null || key == null || h == null || g == null || drive == null || closeType == null) {
            throw new IllegalArgumentException("action, key, h, g, drive and closeType must not be null");
        }
        if (!Double.isFinite(bootstrapTerm)) {
            throw new IllegalArgumentException("bootstrapTerm must be finite: " + bootstrapTerm);
        }
        key = key.clone();
        h = h.clone();
        g = g.clone();
    }

    @Override
    public double[] key() {
        return key.clone();
    }

    @Override
    public double[] h() {
        return h.clone();
    }

    @Override
    public double[] g() {
        return g.clone();
    }

    /** Distance-free read access for the estimator's inner loop: no copy, do not mutate. */
    double[] keyUnsafe() {
        return key;
    }

    double[] gUnsafe() {
        return g;
    }
}
