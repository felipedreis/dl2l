package br.cefetmg.lsi.l2l.creature.arbiter;

import java.util.List;

/**
 * The episode events a {@link FocusTracker} emitted in one cycle, sent in the order they were emitted.
 * Immutable: the list is copied.
 *
 * @param cycle  cycle the events were emitted in
 * @param events the events, in emission order; never empty
 */
public record FocusEvents(int cycle, List<FocusEvent> events) {

    public FocusEvents {
        if (events == null || events.isEmpty()) {
            throw new IllegalArgumentException("events must not be null or empty");
        }
        events = List.copyOf(events);
    }
}
