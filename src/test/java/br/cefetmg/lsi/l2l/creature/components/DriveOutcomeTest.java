package br.cefetmg.lsi.l2l.creature.components;

import br.cefetmg.lsi.l2l.cluster.settings.LearningSettings;
import br.cefetmg.lsi.l2l.common.Constants;
import br.cefetmg.lsi.l2l.common.SequentialId;
import br.cefetmg.lsi.l2l.creature.bd.ActionSelectionType;
import br.cefetmg.lsi.l2l.creature.bd.DriveOutcomeState;
import br.cefetmg.lsi.l2l.creature.common.ActionType;
import br.cefetmg.lsi.l2l.creature.testing.TestingHarness;
import br.cefetmg.lsi.l2l.stimuli.AdrenergicStimulus;
import br.cefetmg.lsi.l2l.stimuli.AnalgesicStimulus;
import br.cefetmg.lsi.l2l.stimuli.CholinergicStimulus;
import br.cefetmg.lsi.l2l.stimuli.NociceptiveStimulus;
import br.cefetmg.lsi.l2l.stimuli.NutritiveStimulus;
import br.cefetmg.lsi.l2l.stimuli.TediumStimulus;
import br.cefetmg.lsi.l2l.world.WorldObjectType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Issue #100: the per-drive outcome vector K is attributed to the act that caused it and is additive. */
public class DriveOutcomeTest {

    private static final WorldObjectType FRUIT = () -> "FRUIT";

    private static TestingHarness harness() {
        return TestingHarness.builder().learningSettings(new LearningSettings(false, false,
                List.of(ActionSelectionType.TARGET_DISTANCE, ActionSelectionType.RANDOM))).build();
    }

    private static double level(TestingHarness h, String drive) {
        return h.creature().emotions().getLevel(drive);
    }

    @Test
    void k_is_attributed_to_the_originating_action() {
        TestingHarness h = harness();
        SequentialId sid = new SequentialId(777L);
        h.creature().emotions().regulate(Constants.HUNGER, 3.0);
        h.creature().emotions().regulate(Constants.SLEEP, 3.0);
        double hunger = level(h, Constants.HUNGER), sleep = level(h, Constants.SLEEP);

        h.inject(HomeostaticRegulation.class, new NutritiveStimulus(sid, sid.next(), FRUIT, 1.0));
        h.inject(HomeostaticRegulation.class, new CholinergicStimulus(sid, sid.next(), 0.5));
        h.inject(HomeostaticRegulation.class, new AdrenergicStimulus(sid, sid.next(), Constants.DELTA));

        List<DriveOutcomeState> rows = h.bdSink().ofType(DriveOutcomeState.class);
        assertEquals(List.of("EAT", "SLEEP", DriveOutcomeState.BASELINE),
                rows.stream().map(DriveOutcomeState::getAction).toList());

        DriveOutcomeState eat = rows.get(0);
        assertEquals(-1.0, eat.getDHunger(), 1e-9);
        assertEquals(0.0, eat.getDSleep(), 1e-9);
        assertEquals("FRUIT", eat.getObjectType());
        assertEquals(sid, eat.getTarget());

        DriveOutcomeState slept = rows.get(1);
        assertEquals(-0.5, slept.getDSleep(), 1e-9);
        assertEquals(0.0, slept.getDHunger(), 1e-9);

        assertTrue(rows.get(2).getDHunger() > 0, "metabolic drift raises hunger, tagged BASELINE");
        assertNull(rows.get(2).getTarget());
        assertEquals(hunger - 1.0 + rows.get(2).getDHunger(), level(h, Constants.HUNGER), 1e-9);
        assertEquals(sleep - 0.5 + rows.get(2).getDSleep(), level(h, Constants.SLEEP), 1e-9);
    }

    @Test
    void passive_pain_without_an_action_is_baseline() {
        TestingHarness h = harness();
        SequentialId sid = new SequentialId(777L);
        h.inject(HomeostaticRegulation.class, new NociceptiveStimulus(sid, sid.next(), 1.0, null, null));
        h.inject(HomeostaticRegulation.class, new NociceptiveStimulus(sid, sid.next(), 1.0, ActionType.APPROACH, FRUIT));

        List<DriveOutcomeState> rows = h.bdSink().ofType(DriveOutcomeState.class);
        // Immune decay (queued above the pain threshold) adds its own BASELINE rows in between.
        assertEquals(DriveOutcomeState.BASELINE, rows.get(0).getAction());
        assertTrue(rows.get(0).getDPain() > 0);
        DriveOutcomeState approach = rows.stream().filter(r -> "APPROACH".equals(r.getAction())).findFirst().orElseThrow();
        assertTrue(approach.getDPain() > 0);
    }

    @Test
    void clamped_change_is_realized_not_intended_and_zero_k_is_not_written() {
        TestingHarness h = harness();
        SequentialId sid = new SequentialId(777L);
        // Hunger starts at its floor: eating cannot lower it, so K is all zero and no row is written.
        h.inject(HomeostaticRegulation.class, new NutritiveStimulus(sid, sid.next(), FRUIT, 5.0));
        assertTrue(h.bdSink().ofType(DriveOutcomeState.class).isEmpty());

        // Partial clamp: only the 1.0 above the floor can be removed.
        h.creature().emotions().regulate(Constants.HUNGER, 1.0);
        h.inject(HomeostaticRegulation.class, new NutritiveStimulus(sid, sid.next(), FRUIT, 5.0));
        DriveOutcomeState row = h.bdSink().ofType(DriveOutcomeState.class).get(0);
        assertEquals(-1.0, row.getDHunger(), 1e-9);
    }

    @Test
    void summed_k_matches_the_total_change_in_each_drive() {
        TestingHarness h = harness();
        SequentialId sid = new SequentialId(777L);
        double h0 = level(h, Constants.HUNGER), s0 = level(h, Constants.SLEEP),
               p0 = level(h, Constants.PAIN),   t0 = level(h, Constants.TEDIUM);

        for (int i = 0; i < 40; i++) {
            h.inject(HomeostaticRegulation.class, new AdrenergicStimulus(sid, sid.next(), Constants.DELTA * 500));
            if (i % 3 == 0) h.inject(HomeostaticRegulation.class, new NutritiveStimulus(sid, sid.next(), FRUIT, 0.7));
            if (i % 4 == 0) h.inject(HomeostaticRegulation.class, new CholinergicStimulus(sid, sid.next(), 0.4));
            if (i % 5 == 0) h.inject(HomeostaticRegulation.class,
                    new NociceptiveStimulus(sid, sid.next(), 0.8, i % 2 == 0 ? ActionType.APPROACH : null, FRUIT));
            if (i % 6 == 0) h.inject(HomeostaticRegulation.class,
                    new AnalgesicStimulus(sid, sid.next(), 0.3, ActionType.SLEEP, null));
            if (i % 7 == 0) h.inject(HomeostaticRegulation.class,
                    new TediumStimulus(sid, sid.next(), i % 2 == 0 ? 0.5 : -0.5, ActionType.WANDER));
        }

        List<DriveOutcomeState> rows = h.bdSink().ofType(DriveOutcomeState.class);
        assertFalse(rows.isEmpty());
        assertEquals(level(h, Constants.HUNGER) - h0, rows.stream().mapToDouble(DriveOutcomeState::getDHunger).sum(), 1e-9);
        assertEquals(level(h, Constants.SLEEP)  - s0, rows.stream().mapToDouble(DriveOutcomeState::getDSleep).sum(),  1e-9);
        assertEquals(level(h, Constants.PAIN)   - p0, rows.stream().mapToDouble(DriveOutcomeState::getDPain).sum(),   1e-9);
        assertEquals(level(h, Constants.TEDIUM) - t0, rows.stream().mapToDouble(DriveOutcomeState::getDTedium).sum(), 1e-9);
    }
}
