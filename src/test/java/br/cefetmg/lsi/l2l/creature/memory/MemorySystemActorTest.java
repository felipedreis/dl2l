package br.cefetmg.lsi.l2l.creature.memory;

import br.cefetmg.lsi.l2l.common.Constants;
import br.cefetmg.lsi.l2l.common.SequentialId;
import br.cefetmg.lsi.l2l.creature.common.ActionType;
import br.cefetmg.lsi.l2l.creature.components.Emotion;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class MemorySystemActorTest {

    private MemorySystemActor store;

    @BeforeEach
    void setUp() {
        store = new MemorySystemActor();
    }

    @Test
    void testGetMemoriesReturnsStoredEngram() {
        SequentialId objectId = new SequentialId(42);
        Emotion emotion = new Emotion("hunger");
        emotion.setLevel(3.0);

        ShortTermMemory stm = new ShortTermMemory(ActionType.EAT, objectId, emotion, null, 1L);
        store.addShortTermMemory(stm);

        List<ShortTermMemory> result = store.getMemories(objectId);
        assertEquals(1, result.size());
        assertEquals(ActionType.EAT, result.get(0).actionType());
        assertEquals(objectId, result.get(0).id());
    }

    @Test
    void testValuationFilterFindsCorrespondingMemory() {
        SequentialId objectId = new SequentialId(7);
        Emotion emotion = new Emotion("hunger");
        emotion.setLevel(2.5);

        store.addShortTermMemory(new ShortTermMemory(ActionType.EAT, objectId, emotion, null, 1L));
        store.addShortTermMemory(new ShortTermMemory(ActionType.APPROACH, objectId, emotion, null, 2L));

        List<ShortTermMemory> memories = store.getMemories(objectId);
        List<ShortTermMemory> correspondingMemories = memories.stream()
                .filter(m -> m.actionType() == ActionType.EAT)
                .toList();

        assertEquals(1, correspondingMemories.size());
        assertEquals(ActionType.EAT, correspondingMemories.get(0).actionType());
    }

    @Test
    void testBoundedEviction() {
        SequentialId oldestId = new SequentialId(0);
        Emotion emotion = new Emotion("sleep");
        emotion.setLevel(1.0);

        store.addShortTermMemory(new ShortTermMemory(ActionType.SLEEP, oldestId, emotion, null, 0L));

        for (int i = 1; i <= 1000; i++) {
            SequentialId id = new SequentialId(i);
            store.addShortTermMemory(new ShortTermMemory(ActionType.WANDER, id, emotion, null, i));
        }

        assertTrue(store.getMemories(oldestId).isEmpty(),
                "Oldest engram should have been evicted once MAX_SIZE is exceeded");
    }

    @Test
    void testEmotionSnapshotIsImmutableAfterStorage() {
        SequentialId objectId = new SequentialId(99);
        Emotion emotion = new Emotion("hunger");
        emotion.setLevel(5.0);

        ShortTermMemory stm = new ShortTermMemory(ActionType.EAT, objectId, emotion, null, 1L);
        store.addShortTermMemory(stm);

        emotion.setLevel(1.0);

        ShortTermMemory retrieved = store.getMemories(objectId).get(0);
        assertEquals(5.0, retrieved.emotion().getLevel(), 1e-9,
                "Stored emotion level must not reflect post-storage mutation of the original");
    }

    @Test
    void testDelayedRewardReinforcesWarmTrace() {
        SequentialId objectId = new SequentialId(10);
        Emotion emotion = new Emotion("hunger");
        emotion.setLevel(4.0);

        store.addShortTermMemory(new ShortTermMemory(ActionType.EAT, objectId, emotion, null, 1L));

        // reinforce at cycle 3: gap = 2, eligibility = exp(-ln2/5 * 2)
        double expectedEligibility = Math.exp(-Math.log(2.0) / Constants.TRACE_DECAY_HALF_LIFE * 2);
        store.reinforceWarmTraces(-0.5, 3L);

        List<Engram> result = store.getRecentEngrams(10);
        assertEquals(1, result.size());
        assertEquals(ActionType.EAT, result.get(0).actionType());
        assertEquals(1L, result.get(0).layCycle());
        assertEquals(3L, result.get(0).reinforcedCycle());
        assertEquals(-0.5 * expectedEligibility, result.get(0).emotionDelta(), 1e-9);
        assertEquals(expectedEligibility, result.get(0).eligibility(), 1e-9);
    }

    @Test
    void testColdTraceNotReinforced() {
        SequentialId objectId = new SequentialId(11);
        Emotion emotion = new Emotion("hunger");
        emotion.setLevel(2.0);

        store.addShortTermMemory(new ShortTermMemory(ActionType.APPROACH, objectId, emotion, null, 1L));

        // gap of 99 cycles: eligibility ≈ 5e-7, well below MIN_TRACE_ELIGIBILITY
        store.reinforceWarmTraces(-0.5, 100L);

        assertTrue(store.getRecentEngrams(10).isEmpty(),
                "Cold trace (gap=99) should not produce an Engram");
    }

    @Test
    void testMultipleWarmTracesAllReinforced() {
        Emotion emotion = new Emotion("hunger");
        emotion.setLevel(3.0);

        store.addShortTermMemory(new ShortTermMemory(ActionType.EAT,     new SequentialId(1), emotion, null, 1L));
        store.addShortTermMemory(new ShortTermMemory(ActionType.APPROACH, new SequentialId(2), emotion, null, 2L));

        store.reinforceWarmTraces(-0.3, 3L);

        List<Engram> result = store.getRecentEngrams(10);
        assertEquals(2, result.size(), "Both warm traces should produce Engrams");
    }
}
