package br.cefetmg.lsi.l2l.creature.memory;

import br.cefetmg.lsi.l2l.common.SequentialId;
import br.cefetmg.lsi.l2l.creature.ComponentRef;
import br.cefetmg.lsi.l2l.creature.arbiter.ClosedStep;
import br.cefetmg.lsi.l2l.creature.arbiter.DecisionRecord;
import br.cefetmg.lsi.l2l.creature.arbiter.FocusEvent;
import br.cefetmg.lsi.l2l.creature.common.Action;
import br.cefetmg.lsi.l2l.creature.common.ActionType;
import br.cefetmg.lsi.l2l.creature.common.Perception;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The death close-out through the real {@link MemorySystemActor}: its {@code closeOnDeath} must return the open
 * episode even when the death cycle (read by {@code CreatureActor.kill()}) is earlier than the last decision the
 * memory system has recorded. Found by the p105 check run (finite_food, creature 343, 71 steps lost with no
 * {@code DEATH} rows).
 */
class MemorySystemEpisodeDeathTest {

    private static final String HUNGER = "hunger";
    private static final double[] HUNGRY = {2, 0, 0, 0};

    private static Action wander(int id) {
        return new Action(ActionType.WANDER, new Perception(null, new SequentialId(id), 10, 0));
    }

    @Test
    void closeOnDeathReturnsTheOpenEpisodeWhenTheDeathCycleIsBeforeTheLastDecision() {
        MemorySystemActor memory = new MemorySystemActor();
        ComponentRef noValuation = msg -> { };
        memory.enableEpisodes(noValuation);

        memory.recordFocusEvents(List.of(new FocusEvent.Opened(3, HUNGER)));
        for (int cycle = 3; cycle <= 5; cycle++) {
            memory.recordDecision(new DecisionRecord(cycle, wander(cycle), HUNGRY, HUNGER, Double.NaN));
        }

        List<ClosedStep> steps = memory.closeOnDeath(4, new double[] {7, 0, 0, 0});

        assertEquals(3, steps.size());
    }
}
