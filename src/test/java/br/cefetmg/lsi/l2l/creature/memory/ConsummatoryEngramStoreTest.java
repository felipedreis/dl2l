package br.cefetmg.lsi.l2l.creature.memory;

import br.cefetmg.lsi.l2l.common.SequentialId;
import br.cefetmg.lsi.l2l.creature.common.ActionType;
import br.cefetmg.lsi.l2l.creature.common.Perception;
import br.cefetmg.lsi.l2l.creature.components.Emotion;
import br.cefetmg.lsi.l2l.world.FruitType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Consummatory engrams live in their own FIFO so the flood of approach traces cannot evict them.
 *
 * <p>The shared store made memory structurally incapable of learning what to eat. Measured over
 * the p84 pilot: consummatory acts are 0.82% of engrams, so a single 1000-entry FIFO retains about
 * EIGHT of them while a creature accumulates ~720 feedings in its life. At ~290 engrams/second the
 * whole store turns over every 3.45 s, so a feeding memory — or a consolidated trace, which is
 * written into the same deque — survives about three seconds.
 */
public class ConsummatoryEngramStoreTest {

    private static final int CAP = 1000;   // MemorySystemActor.MAX_ENGRAM_SIZE

    @Test
    void approach_traces_cannot_evict_consummatory_memories() {
        MemorySystemActor memory = new MemorySystemActor();

        memory.addEngram(engram(ActionType.EAT, FruitType.GREEN_APPLE));
        // Far more approach traces than the shared store could ever hold.
        for (int i = 0; i < CAP * 3; i++) {
            memory.addEngram(engram(ActionType.APPROACH, FruitType.GRAY_APPLE));
        }

        assertTrue(memory.getRecentEngrams(CAP).stream().noneMatch(e -> e.actionType() == ActionType.EAT),
                "the shared store has been flushed — that is the behaviour being worked around");

        List<Engram> consummatory = memory.getRecentConsummatoryEngrams(CAP);
        assertEquals(1, consummatory.size(), "the feeding memory must survive the flood");
        assertEquals(FruitType.GREEN_APPLE, consummatory.get(0).perception().objectType.getOrElse(null));
    }

    @Test
    void the_consummatory_store_is_bounded_too() {
        MemorySystemActor memory = new MemorySystemActor();
        for (int i = 0; i < CAP * 2; i++) {
            memory.addEngram(engram(ActionType.EAT, FruitType.RED_APPLE));
        }
        assertEquals(CAP, memory.getRecentConsummatoryEngrams(CAP * 5).size(),
                "unbounded growth would just move the leak, not fix it");
    }

    @Test
    void every_consummatory_action_type_is_retained() {
        // Mapa stores comer, tocar and brincar — not EAT alone.
        MemorySystemActor memory = new MemorySystemActor();
        for (ActionType t : ActionType.values()) {
            memory.addEngram(engram(t, FruitType.RED_APPLE));
        }
        List<ActionType> kept = memory.getRecentConsummatoryEngrams(CAP)
                .stream().map(Engram::actionType).toList();
        assertEquals(ActionType.CONSUMMATORY, java.util.EnumSet.copyOf(kept));
    }

    @Test
    void consummatory_engrams_remain_in_the_general_store_as_well() {
        // The general store still backs consolidation and any full-history reader; this store
        // exists to stop eviction, not to reclassify traces out of the other one.
        MemorySystemActor memory = new MemorySystemActor();
        memory.addEngram(engram(ActionType.EAT, FruitType.GREEN_APPLE));

        assertEquals(1, memory.getRecentEngrams(CAP).size());
        assertEquals(1, memory.getRecentConsummatoryEngrams(CAP).size());
    }

    @Test
    void window_size_returns_the_most_recent_entries() {
        MemorySystemActor memory = new MemorySystemActor();
        for (int i = 0; i < 10; i++) {
            memory.addEngram(new Engram(ActionType.EAT, new SequentialId(i), emotion(),
                    new Perception(FruitType.RED_APPLE, new SequentialId(i), 10, 0),
                    i, -1.0, 1.0, i));
        }
        List<Engram> last3 = memory.getRecentConsummatoryEngrams(3);
        assertEquals(3, last3.size());
        assertEquals(List.of(7L, 8L, 9L), last3.stream().map(Engram::layCycle).toList());
    }

    private static Engram engram(ActionType type, FruitType object) {
        return new Engram(type, new SequentialId(1), emotion(),
                new Perception(object, new SequentialId(1), 10, 0), 1L, -1.0, 1.0, 1L);
    }

    private static Emotion emotion() {
        Emotion e = new Emotion("hunger");
        e.setLevel(3.0);
        return e;
    }
}
