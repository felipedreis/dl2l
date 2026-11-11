package br.cefetmg.lsi.l2l.creature.arbiter;

import br.cefetmg.lsi.l2l.creature.common.ActionType;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.Map;

/**
 * The long-term episodic store: one bounded FIFO per action type, the paper's per-action store {@code M_a}
 * (section 4.2, issue #111). Retrieval is per action, so a flood of one action's engrams (APPROACH) can evict
 * only its own.
 *
 * <p>Not thread-safe: it lives behind the {@link EpisodicStore} facade in {@code CreatureActor}.
 */
public final class EpisodicEngramStore {

    /** Engrams kept per action (docs/plans/issue-111-episodic-engram.md). */
    public static final int DEFAULT_CAP_PER_ACTION = 2000;

    private final int capPerAction;
    private final Map<ActionType, ArrayDeque<EpisodicEngram>> byAction = new EnumMap<>(ActionType.class);
    private int size;

    /** {@link #DEFAULT_CAP_PER_ACTION}. */
    public EpisodicEngramStore() {
        this(DEFAULT_CAP_PER_ACTION);
    }

    /**
     * @param capPerAction engrams kept per action, at least 1
     * @throws IllegalArgumentException if {@code capPerAction} is below 1
     */
    public EpisodicEngramStore(int capPerAction) {
        if (capPerAction < 1) {
            throw new IllegalArgumentException("capPerAction must be at least 1: " + capPerAction);
        }
        this.capPerAction = capPerAction;
    }

    /** Adds an engram; when its action's FIFO is full the oldest one of that action is dropped. */
    public void add(EpisodicEngram engram) {
        if (engram == null) {
            throw new IllegalArgumentException("engram must not be null");
        }
        ArrayDeque<EpisodicEngram> bucket = byAction.computeIfAbsent(engram.action(), a -> new ArrayDeque<>());
        bucket.addLast(engram);
        size++;
        if (bucket.size() > capPerAction) {
            bucket.pollFirst();
            size--;
        }
    }

    /** The engrams of one action, oldest first; a live read-only view, empty when there are none. */
    public Iterable<EpisodicEngram> of(ActionType action) {
        ArrayDeque<EpisodicEngram> bucket = byAction.get(action);
        if (bucket == null) {
            return Collections.emptyList();
        }
        return () -> {
            Iterator<EpisodicEngram> it = bucket.iterator();
            return new Iterator<>() {
                @Override public boolean hasNext() { return it.hasNext(); }
                @Override public EpisodicEngram next() { return it.next(); }
            };
        };
    }

    /** Engrams held for one action. */
    public int size(ActionType action) {
        ArrayDeque<EpisodicEngram> bucket = byAction.get(action);
        return bucket == null ? 0 : bucket.size();
    }

    /** Engrams held in all. */
    public int size() {
        return size;
    }
}
