package br.cefetmg.lsi.l2l.creature.arbiter;

import br.cefetmg.lsi.l2l.common.SequentialId;
import br.cefetmg.lsi.l2l.creature.common.Action;
import br.cefetmg.lsi.l2l.creature.common.ActionType;
import br.cefetmg.lsi.l2l.creature.common.Perception;
import br.cefetmg.lsi.l2l.world.FruitType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests of {@link EpisodicEngram}, {@link EpisodicEngramStore} and {@link EngramWriter} (issue #111). */
class EngramWriterTest {

    private static final double[] H = {3.0, 0.5, 0.2, 0.2};
    private static final HomeostaticValue VALUE = HomeostaticValue.defaults();

    private static Action act(ActionType type, FruitType obj, double distance) {
        return new Action(type, new Perception(obj, new SequentialId(5), distance, 0.4));
    }

    private static EpisodicEngram engram(ActionType action, long cycle) {
        return new EpisodicEngram(action, "RED_APPLE", new double[EngramKeyEncoder.KEY_DIM], H, new double[4], 0, 1,
                "hunger", ClosedStep.CloseType.REGULATED, (int) cycle, 2);
    }

    @Test
    void storeKeepsAtMostCapPerActionAndDropsTheOldestOfThatActionOnly() {
        EpisodicEngramStore store = new EpisodicEngramStore(3);
        for (int c = 0; c < 5; c++) {
            store.add(engram(ActionType.APPROACH, c));
        }
        store.add(engram(ActionType.EAT, 100));
        assertEquals(3, store.size(ActionType.APPROACH));
        assertEquals(1, store.size(ActionType.EAT));
        assertEquals(4, store.size());
        List<Integer> cycles = new ArrayList<>();
        store.of(ActionType.APPROACH).forEach(e -> cycles.add(e.stepCycle()));
        assertEquals(List.of(2, 3, 4), cycles, "oldest first, the two oldest were evicted");
        assertTrue(!store.of(ActionType.AVOID).iterator().hasNext());
    }

    @Test
    void storeRejectsBadInput() {
        assertThrows(IllegalArgumentException.class, () -> new EpisodicEngramStore(0));
        assertThrows(IllegalArgumentException.class, () -> new EpisodicEngramStore().add(null));
    }

    @Test
    void engramCopiesItsArrays() {
        double[] g = {-1, 0, 0, 0};
        EpisodicEngram e = new EpisodicEngram(ActionType.EAT, null, new double[EngramKeyEncoder.KEY_DIM], H, g, 0, 1,
                "hunger", ClosedStep.CloseType.REGULATED, 1, 1);
        g[0] = 99;
        assertEquals(-1, e.g()[0]);
        e.g()[1] = 5;
        assertEquals(0, e.g()[1]);
    }

    @Test
    void writerEncodesTheKeyAndKeepsTheStepsFields() {
        List<EpisodicEngram> got = new ArrayList<>();
        EngramKeyEncoder enc = new EngramKeyEncoder();
        EngramWriter writer = new EngramWriter(enc, VALUE, got::addAll);
        Action eat = act(ActionType.EAT, FruitType.GREEN_APPLE, 5);
        double[] g = {-1.5, 0, 0, 0};
        writer.onClosed(List.of(new ClosedStep(7, "hunger", ClosedStep.CloseType.REGULATED, 10, 13, eat, H, g,
                Double.NaN, VALUE.value(g, H))));

        assertEquals(1, got.size());
        EpisodicEngram e = got.get(0);
        assertEquals(ActionType.EAT, e.action());
        assertEquals("GREEN_APPLE", e.objectType());
        assertEquals(7, e.episodeId());
        assertEquals("hunger", e.drive());
        assertEquals(10, e.stepCycle());
        assertEquals(3, e.stepsToOutcome());
        assertEquals(ClosedStep.CloseType.REGULATED, e.closeType());
        assertArrayEquals(enc.encode(eat.perception, H), e.key(), 0.0);
        assertArrayEquals(H, e.h(), 0.0);
        assertArrayEquals(g, e.g(), 0.0);
        assertEquals(0.0, e.bootstrapTerm(), 0.0);
    }

    @Test
    void aPreemptedStepKeepsItsBootstrapTermAsValueMinusV() {
        List<EpisodicEngram> got = new ArrayList<>();
        EngramWriter writer = new EngramWriter(new EngramKeyEncoder(), VALUE, got::addAll);
        double[] g = {-0.5, 0, 0, 0};
        double b = 0.8, gamma = EpisodeBuffer.DEFAULT_GAMMA;
        double value = VALUE.value(g, H) + Math.pow(gamma, 2) * b;
        writer.onClosed(List.of(new ClosedStep(1, "hunger", ClosedStep.CloseType.PREEMPTED, 4, 6,
                act(ActionType.APPROACH, FruitType.RED_APPLE, 30), H, g, b, value)));
        assertEquals(Math.pow(gamma, 2) * b, got.get(0).bootstrapTerm(), 1e-12);
    }

    @Test
    void aTimedOutApproachHasAZeroReturnAndNoBootstrap() {
        List<EpisodicEngram> got = new ArrayList<>();
        EngramWriter writer = new EngramWriter(new EngramKeyEncoder(), VALUE, got::addAll);
        writer.onClosed(List.of(new ClosedStep(1, "hunger", ClosedStep.CloseType.TIMED_OUT, 4, 204,
                act(ActionType.APPROACH, FruitType.GRAY_APPLE, 30), H, new double[4], Double.NaN, 0)));
        assertArrayEquals(new double[4], got.get(0).g(), 0.0);
        assertEquals(0.0, got.get(0).bootstrapTerm(), 0.0);
        assertEquals(200, got.get(0).stepsToOutcome());
    }

    @Test
    void writerPassesSegmentsInStepOrderAndNothingForAnEmptyList() {
        List<List<EpisodicEngram>> batches = new ArrayList<>();
        EngramWriter writer = new EngramWriter(new EngramKeyEncoder(), VALUE, batches::add);
        writer.onClosed(List.of());
        assertTrue(batches.isEmpty());
        writer.onClosed(List.of(
                new ClosedStep(1, "hunger", ClosedStep.CloseType.REGULATED, 1, 3, act(ActionType.APPROACH, FruitType.RED_APPLE, 40), H, new double[4], Double.NaN, 0),
                new ClosedStep(1, "hunger", ClosedStep.CloseType.REGULATED, 2, 3, act(ActionType.EAT, FruitType.RED_APPLE, 5), H, new double[4], Double.NaN, 0)));
        assertEquals(List.of(1, 2), batches.get(0).stream().map(EpisodicEngram::stepCycle).toList());
    }

    @Test
    void writerFedByARealEpisodeBufferWritesTheClosedEpisodeAsEngrams() {
        List<EpisodicEngram> got = new ArrayList<>();
        EpisodeBuffer buffer = new EpisodeBuffer(VALUE);
        buffer.addSink(new EngramWriter(new EngramKeyEncoder(), VALUE, got::addAll));
        buffer.onFocus(new FocusEvent.Opened(1, "hunger"));
        buffer.onDecision(1, act(ActionType.APPROACH, FruitType.RED_APPLE, 40), H, "hunger", Double.NaN);
        buffer.onDecision(2, act(ActionType.EAT, FruitType.RED_APPLE, 5), H, "hunger", Double.NaN);
        buffer.onOutcome(2, new double[]{-1.0, 0, 0, 0});
        buffer.onFocus(new FocusEvent.Closed(3, "hunger", FocusEvent.Outcome.REGULATED));
        buffer.onDecision(4, act(ActionType.WANDER, FruitType.RED_APPLE, 5), H, null, Double.NaN);

        assertEquals(2, got.size());
        assertEquals(ActionType.APPROACH, got.get(0).action());
        assertEquals(ActionType.EAT, got.get(1).action());
        // the approach step is credited with what followed it: gamma * K
        assertEquals(-EpisodeBuffer.DEFAULT_GAMMA, got.get(0).g()[0], 1e-12);
        assertEquals(-1.0, got.get(1).g()[0], 1e-12);
    }
}
