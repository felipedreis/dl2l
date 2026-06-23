package br.cefetmg.lsi.l2l.creature.memory;

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
        // Fill store to MAX_SIZE + 1; oldest entry should be evicted
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

        // Mutate the original emotion after storage
        emotion.setLevel(1.0);

        ShortTermMemory retrieved = store.getMemories(objectId).get(0);
        assertEquals(5.0, retrieved.emotion().getLevel(), 1e-9,
                "Stored emotion level must not reflect post-storage mutation of the original");
    }
}
