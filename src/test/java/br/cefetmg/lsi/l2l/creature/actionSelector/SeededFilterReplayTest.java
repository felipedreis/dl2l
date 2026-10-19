package br.cefetmg.lsi.l2l.creature.actionSelector;

import br.cefetmg.lsi.l2l.common.Seeds;
import br.cefetmg.lsi.l2l.common.SequentialId;
import br.cefetmg.lsi.l2l.creature.common.Action;
import br.cefetmg.lsi.l2l.creature.common.ActionType;
import br.cefetmg.lsi.l2l.creature.common.Perception;
import br.cefetmg.lsi.l2l.world.Self;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/** Issue #95: a filter built from a derived seed replays the same draw sequence. */
public class SeededFilterReplayTest {

    private static List<ActionType> draws(long rootSeed, long creatureKey, int n) {
        RandomFilter filter = new RandomFilter(new Random(Seeds.derive(rootSeed, "creature", creatureKey, "randomFilter")));
        Perception p = new Perception(Self.get(), new SequentialId(creatureKey), 0, 0);
        List<Action> candidates = List.of(new Action(ActionType.EAT, p), new Action(ActionType.WANDER, p),
                new Action(ActionType.SLEEP, p), new Action(ActionType.APPROACH, p), new Action(ActionType.OBSERVE, p));
        List<ActionType> out = new ArrayList<>();
        for (int i = 0; i < n; i++) out.add(filter.filter(candidates, null).get(0).type);
        return out;
    }

    @Test
    public void sameSeedAndCreatureReplaysTheSequence() {
        assertEquals(draws(42, 7, 500), draws(42, 7, 500));
    }

    @Test
    public void otherCreaturesAndSeedsDrawOtherSequences() {
        assertNotEquals(draws(42, 7, 500), draws(42, 8, 500));
        assertNotEquals(draws(42, 7, 500), draws(43, 7, 500));
    }
}
