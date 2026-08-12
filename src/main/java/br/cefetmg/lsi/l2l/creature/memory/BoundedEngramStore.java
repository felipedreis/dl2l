package br.cefetmg.lsi.l2l.creature.memory;

import br.cefetmg.lsi.l2l.creature.common.ActionType;
import br.cefetmg.lsi.l2l.world.WorldObjectType;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Engram store that bounds retention <em>per (action, object) key</em> rather than globally.
 *
 * <h2>Why not one FIFO</h2>
 * A single flat FIFO evicts by age alone, so the store's composition converges on whatever the
 * creature does most — which is precisely what carries the least information. Measured over the
 * p84 pilot: APPROACH-on-GRAY_APPLE accounted for 116,501 of ~264,000 engrams, all of them
 * near-duplicates discriminating objects at 1.09x, while the 1,021 EAT-on-GRAY engrams that
 * actually say what gray is worth discriminate at 6.3x. At ~290 engrams/second a 1000-entry flat
 * FIFO turns over completely every 3.45 s, so rare and informative traces are evicted by common
 * and redundant ones within seconds of being laid.
 *
 * <p>Capping each key independently makes the common ones expire against each other instead of
 * against everything else. A rare experience persists as long as it stays rare.
 *
 * <h2>Why not a salience priority queue</h2>
 * Retaining the most emotionally striking traces is the intuitive alternative and matches
 * McGaugh's amygdala-modulated consolidation, but applied to raw {@code |emotionDelta|} it breaks
 * the estimator it feeds. {@link br.cefetmg.lsi.l2l.creature.actionSelector.MemoryFilter} values an
 * object by the MEAN of its engrams, which is only a valid estimate of expected value if retention
 * is unbiased with respect to the value being estimated. Salience-weighted eviction is biased
 * exactly there — and differentially per object, which is the comparison being made.
 *
 * <p>The pilot shows how badly. EAT's MEDIAN {@code |emotionDelta|} (0.0021) is <em>lower</em> than
 * APPROACH's (0.0047); only its tail is high. Within EAT, gray scores 0.0084 against green's
 * 0.0308. So a salience queue preferentially discards the memory of a disappointing meal — the one
 * memory required to learn that a food is worthless — leaving a store that says all food is good.
 * Keeping the top 1% by salience yields a store still 97.6% approach traces.
 *
 * <p>Per-key capping gets the intended effect (common expires faster, rare persists) while keeping
 * each key's retained set an unbiased sample <em>of that key</em>, so per-key and per-object means
 * stay meaningful. If a graded priority is wanted later, the defensible signal is surprise
 * (|RPE|, already computed by the expectancy subsystem) rather than raw emotional magnitude: an
 * unrewarding meal is low-magnitude but high-surprise, so it is retained rather than discarded.
 *
 * <h2>Ordering</h2>
 * {@link #recent} returns a sample, not a strict recency window: entries are ordered by
 * {@code reinforcedCycle} across keys, but a key that has been quiet still contributes its old
 * entries. Both consumers (MemoryFilter's per-object mean, MemoryTraceConsolidator's per-group
 * mean) are order-insensitive aggregates, so this is the intended reading rather than a
 * concession.
 */
public class BoundedEngramStore {

    private final int maxPerKey;
    private final Map<Key, ArrayDeque<Engram>> byKey = new LinkedHashMap<>();
    private int size;

    public BoundedEngramStore(int maxPerKey) {
        this.maxPerKey = maxPerKey;
    }

    public void add(Engram engram) {
        Key key = keyOf(engram);
        ArrayDeque<Engram> bucket = byKey.computeIfAbsent(key, k -> new ArrayDeque<>());
        bucket.addLast(engram);
        size++;
        if (bucket.size() > maxPerKey) {
            bucket.pollFirst();
            size--;
        }
    }

    /** Up to {@code windowSize} entries, most recent by reinforcedCycle, oldest-first. */
    public List<Engram> recent(int windowSize) {
        List<Engram> all = new ArrayList<>(size);
        for (ArrayDeque<Engram> bucket : byKey.values()) {
            all.addAll(bucket);
        }
        all.sort(Comparator.comparingLong(Engram::reinforcedCycle));
        int skip = Math.max(0, all.size() - windowSize);
        return all.subList(skip, all.size());
    }

    public int size() {
        return size;
    }

    /** Distinct (action, object) keys currently held — the store's stratification width. */
    public int keyCount() {
        return byKey.size();
    }

    private static Key keyOf(Engram e) {
        WorldObjectType objType = e.perception() != null
                ? e.perception().objectType.getOrElse(null) : null;
        return new Key(e.actionType(), objType);
    }

    private record Key(ActionType actionType, WorldObjectType objectType) {
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Key other)) return false;
            return actionType == other.actionType && Objects.equals(objectType, other.objectType);
        }

        @Override
        public int hashCode() {
            return Objects.hash(actionType, objectType);
        }
    }
}
