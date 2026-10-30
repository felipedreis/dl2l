package br.cefetmg.lsi.l2l.creature.arbiter;

import java.util.List;

/**
 * Sent by the memory system to {@code Valuation} when episode steps have been written by the
 * {@link EpisodeBuffer}, so Valuation can value them into the operant store and persist them.
 * Immutable: the list is copied.
 *
 * @param steps the written steps, in decision order; never empty
 */
public record ClosedEpisodes(List<ClosedStep> steps) {

    public ClosedEpisodes {
        if (steps == null || steps.isEmpty()) {
            throw new IllegalArgumentException("steps must not be null or empty");
        }
        steps = List.copyOf(steps);
    }
}
