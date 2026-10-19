package br.cefetmg.lsi.l2l.creature.arbiter;

/**
 * Episode lifecycle events emitted by {@link FocusTracker}. Immutable.
 */
public sealed interface FocusEvent {

    enum Outcome { REGULATED, TIMED_OUT, DEATH }

    int cycle();

    String drive();

    record Opened(int cycle, String drive) implements FocusEvent { }

    record Closed(int cycle, String drive, Outcome outcome) implements FocusEvent { }

    /** {@code by} is the drive that pre-empted {@code drive}. */
    record Suspended(int cycle, String drive, String by) implements FocusEvent { }

    record Resumed(int cycle, String drive) implements FocusEvent { }
}
