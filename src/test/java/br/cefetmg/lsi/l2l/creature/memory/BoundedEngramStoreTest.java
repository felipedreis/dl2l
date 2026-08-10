package br.cefetmg.lsi.l2l.creature.memory;

import br.cefetmg.lsi.l2l.common.SequentialId;
import br.cefetmg.lsi.l2l.creature.common.ActionType;
import br.cefetmg.lsi.l2l.creature.common.Perception;
import br.cefetmg.lsi.l2l.creature.components.Emotion;
import br.cefetmg.lsi.l2l.world.FruitType;
import br.cefetmg.lsi.l2l.world.WorldObjectType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Per-key retention: common experience expires against itself, rare experience persists.
 *
 * <p>The property that matters is not "the store is bounded" but "the store's composition stops
 * tracking behaviour frequency". A flat FIFO's contents converge on whatever the creature does
 * most, which is exactly what carries the least information — in the p84 pilot, 116,501 near
 * identical APPROACH-on-GRAY engrams crowding out the 1,021 EAT-on-GRAY engrams that say what gray
 * is actually worth.
 */
public class BoundedEngramStoreTest {

    private static final int CAP = 8;

    @Test
    void a_flood_of_one_key_cannot_evict_another_key() {
        BoundedEngramStore store = new BoundedEngramStore(CAP);

        store.add(engram(ActionType.EAT, FruitType.GRAY_APPLE, 1));
        for (int i = 0; i < 10_000; i++) {
            store.add(engram(ActionType.APPROACH, FruitType.GRAY_APPLE, 2 + i));
        }

        List<Engram> kept = store.recent(Integer.MAX_VALUE);
        assertTrue(kept.stream().anyMatch(e -> e.actionType() == ActionType.EAT),
                "the single rare EAT memory must survive 10,000 approaches — under a flat FIFO it "
                        + "would have been evicted within the first few hundred");
    }

    @Test
    void each_key_is_capped_independently() {
        BoundedEngramStore store = new BoundedEngramStore(CAP);
        for (int i = 0; i < 100; i++) {
            store.add(engram(ActionType.APPROACH, FruitType.GRAY_APPLE, i));
            store.add(engram(ActionType.EAT, FruitType.GREEN_APPLE, i));
        }

        Map<ActionType, Long> byAction = store.recent(Integer.MAX_VALUE).stream()
                .collect(Collectors.groupingBy(Engram::actionType, Collectors.counting()));
        assertEquals(Map.of(ActionType.APPROACH, (long) CAP, ActionType.EAT, (long) CAP), byAction,
                "both keys keep exactly their cap, regardless of arrival rate");
        assertEquals(2 * CAP, store.size());
        assertEquals(2, store.keyCount());
    }

    @Test
    void within_a_key_the_most_recent_are_kept() {
        // Retention is unbiased WITHIN a key — a recency window, not a salience filter — so the
        // per-key mean stays a valid estimate of that key's recent expected value.
        BoundedEngramStore store = new BoundedEngramStore(CAP);
        for (int i = 0; i < 100; i++) {
            store.add(engram(ActionType.EAT, FruitType.RED_APPLE, i));
        }
        List<Long> cycles = store.recent(Integer.MAX_VALUE).stream().map(Engram::layCycle).toList();
        assertEquals(List.of(92L, 93L, 94L, 95L, 96L, 97L, 98L, 99L), cycles);
    }

    @Test
    void a_low_salience_memory_is_retained_if_its_key_is_rare() {
        // The explicit contrast with a salience priority queue. "Eating gray changed nothing" is
        // the LOWEST-magnitude memory here and the one most needed to learn gray is worthless; a
        // queue ordered by |emotionDelta| discards it first. Per-key retention keeps it because
        // its key is rare, not because it is dramatic.
        BoundedEngramStore store = new BoundedEngramStore(CAP);
        store.add(new Engram(ActionType.EAT, new SequentialId(1), emotion(),
                perception(FruitType.GRAY_APPLE), 1L, -0.0001, 1.0, 1L));   // barely any change
        for (int i = 0; i < 1000; i++) {
            store.add(new Engram(ActionType.APPROACH, new SequentialId(2), emotion(),
                    perception(FruitType.GREEN_APPLE), 2L + i, -5.0, 1.0, 2L + i));  // dramatic
        }

        assertTrue(store.recent(Integer.MAX_VALUE).stream()
                        .anyMatch(e -> e.actionType() == ActionType.EAT),
                "the disappointing meal must survive a flood of dramatic approaches");
    }

    @Test
    void null_object_types_form_their_own_key() {
        BoundedEngramStore store = new BoundedEngramStore(CAP);
        store.add(engram(ActionType.WANDER, null, 1));
        for (int i = 0; i < 100; i++) {
            store.add(engram(ActionType.WANDER, FruitType.RED_APPLE, 2 + i));
        }
        assertEquals(2, store.keyCount(), "self-directed acts must not share a key with objects");
    }

    @Test
    void recent_returns_at_most_the_window_and_is_ordered() {
        BoundedEngramStore store = new BoundedEngramStore(CAP);
        for (int i = 0; i < 20; i++) {
            store.add(engram(ActionType.EAT, FruitType.RED_APPLE, i));
            store.add(engram(ActionType.APPROACH, FruitType.GREEN_APPLE, i));
        }
        List<Engram> window = store.recent(5);
        assertEquals(5, window.size());
        for (int i = 1; i < window.size(); i++) {
            assertTrue(window.get(i - 1).reinforcedCycle() <= window.get(i).reinforcedCycle(),
                    "oldest-first ordering");
        }
    }

    private static Engram engram(ActionType type, WorldObjectType object, long cycle) {
        return new Engram(type, new SequentialId(1), emotion(), perception(object),
                cycle, -1.0, 1.0, cycle);
    }

    private static Perception perception(WorldObjectType object) {
        return new Perception(object, new SequentialId(1), 10, 0);
    }

    private static Emotion emotion() {
        Emotion e = new Emotion("hunger");
        e.setLevel(3.0);
        return e;
    }
}
