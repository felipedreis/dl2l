package br.cefetmg.lsi.l2l.creature.arbiter;

import br.cefetmg.lsi.l2l.common.SequentialId;
import br.cefetmg.lsi.l2l.creature.arbiter.ClosedStep.CloseType;
import br.cefetmg.lsi.l2l.creature.common.Action;
import br.cefetmg.lsi.l2l.creature.common.ActionType;
import br.cefetmg.lsi.l2l.creature.common.Perception;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests of {@link EpisodeBuffer} against the real implementation, with hand-computed expectations.
 *
 * <p>Most cases use {@code gamma = 0.5} and a value function with set point 0 and unit weights, so
 * {@code d(h) = ||max(0, h)||}. Hunger {@code h = (2, 0, 0, 0)} then has deficit 2, and an outcome
 * {@code K = (-1, 0, 0, 0)} lowers it to 1. Drive order is {@link FocusTracker#DRIVES}:
 * hunger, sleep, pain, tedium.
 */
class EpisodeBufferTest {

    private static final String HUNGER = "hunger";
    private static final String PAIN = "pain";
    private static final double GAMMA = 0.5;
    private static final double TOL = 1e-9;
    private static final double[] HUNGRY = {2, 0, 0, 0};
    private static final double[] PAINFUL = {0, 0, 3, 0};
    private static final double[] NO_K = {0, 0, 0, 0};
    private static final double[] RELIEF = {-1, 0, 0, 0};

    private static HomeostaticValue zeroSetPoint() {
        return new HomeostaticValue(new double[4], new double[] {1, 1, 1, 1});
    }

    private static Action act(ActionType type, int id) {
        return new Action(type, new Perception(null, new SequentialId(id), 10, 0));
    }

    private static void decide(EpisodeBuffer b, int cycle, ActionType type, double[] h, String focal, double q) {
        b.onDecision(cycle, act(type, cycle), h, focal, q);
    }

    /** A real {@link ReturnSink} that records what the buffer writes. */
    private static final class Collector implements ReturnSink {
        final List<List<ClosedStep>> calls = new ArrayList<>();

        @Override
        public void onClosed(List<ClosedStep> steps) {
            calls.add(steps);
        }

        List<ClosedStep> all() {
            List<ClosedStep> out = new ArrayList<>();
            calls.forEach(out::addAll);
            return out;
        }
    }

    private static ClosedStep at(List<ClosedStep> steps, int cycle) {
        return steps.stream().filter(s -> s.cycle() == cycle).findFirst()
                .orElseThrow(() -> new AssertionError("no step at cycle " + cycle + " in " + steps));
    }

    // 1. Regulated: Monte Carlo returns to the close cycle; value = V(G | H).
    @Test
    void regulatedEpisodeReturnsMonteCarloSumsToTheCloseCycle() {
        EpisodeBuffer b = new EpisodeBuffer(zeroSetPoint(), GAMMA, 1);
        Collector c = new Collector();
        b.addSink(c);

        b.onFocus(new FocusEvent.Opened(3, HUNGER));
        decide(b, 3, ActionType.APPROACH, HUNGRY, HUNGER, Double.NaN);
        decide(b, 4, ActionType.EAT, HUNGRY, HUNGER, Double.NaN);
        decide(b, 5, ActionType.APPROACH, HUNGRY, HUNGER, Double.NaN);
        b.onOutcome(4, RELIEF);
        b.onFocus(new FocusEvent.Closed(6, HUNGER, FocusEvent.Outcome.REGULATED));

        decide(b, 6, null, NO_K, null, Double.NaN);
        assertEquals(0, c.calls.size(), "a close is held until the next decision settles it");
        decide(b, 7, null, NO_K, null, Double.NaN);

        assertEquals(1, c.calls.size());
        List<ClosedStep> steps = c.calls.get(0);
        assertEquals(3, steps.size());
        // G_5 = 0; G_4 = -1; G_3 = 0.5 * -1 = -0.5. Values: d(2) - d(2 + G) = 2 - 2 - G_norm.
        ClosedStep s3 = at(steps, 3);
        ClosedStep s4 = at(steps, 4);
        ClosedStep s5 = at(steps, 5);
        assertArrayEquals(new double[] {-0.5, 0, 0, 0}, s3.g(), TOL);
        assertArrayEquals(new double[] {-1, 0, 0, 0}, s4.g(), TOL);
        assertArrayEquals(new double[] {0, 0, 0, 0}, s5.g(), TOL);
        assertEquals(2 - 1.5, s3.value(), TOL);
        assertEquals(2 - 1, s4.value(), TOL);
        assertEquals(0, s5.value(), TOL);
        assertTrue(s4.value() > s3.value(), "the eat step carries the largest credit");
        for (ClosedStep s : steps) {
            assertEquals(CloseType.REGULATED, s.closeType());
            assertEquals(6, s.closeCycle());
            assertEquals(0L, s.episodeId());
            assertEquals(HUNGER, s.drive());
            assertTrue(Double.isNaN(s.bootstrap()), "bootstrap is NaN unless pre-empted");
        }
    }

    // 2. Pre-empted: cut at the cut cycle; value adds gamma^(T-t) * B; the same drive resumes as a new segment.
    @Test
    void preemptedEpisodeIsCutAndAddsTheBootstrapOfTheDecisionAtTheCut() {
        EpisodeBuffer b = new EpisodeBuffer(zeroSetPoint(), GAMMA, 1);
        Collector c = new Collector();
        b.addSink(c);

        b.onFocus(new FocusEvent.Opened(3, HUNGER));
        decide(b, 3, ActionType.APPROACH, HUNGRY, HUNGER, Double.NaN);
        decide(b, 4, ActionType.EAT, HUNGRY, HUNGER, Double.NaN);
        b.onOutcome(4, RELIEF);

        // Focus moves at cycle 5. Events come first, then the decision made under the new focus.
        b.onFocus(new FocusEvent.Suspended(5, HUNGER, PAIN));
        b.onFocus(new FocusEvent.Opened(5, PAIN));
        decide(b, 5, ActionType.AVOID, PAINFUL, PAIN, 0.8);
        assertEquals(0, c.calls.size());
        decide(b, 6, ActionType.AVOID, PAINFUL, PAIN, Double.NaN);

        assertEquals(1, c.calls.size());
        List<ClosedStep> steps = c.calls.get(0);
        assertEquals(2, steps.size());
        // Cut at T = 5, B = Q of the decision at 5 = 0.8.
        // G_4 = -1 and G_3 = -0.5 (nothing after the cut). value = V + gamma^(T-t) * B.
        ClosedStep s3 = at(steps, 3);
        ClosedStep s4 = at(steps, 4);
        assertEquals((2 - 1.5) + 0.25 * 0.8, s3.value(), TOL);
        assertEquals((2 - 1) + 0.5 * 0.8, s4.value(), TOL);
        assertEquals(0.8, s3.bootstrap(), TOL);
        assertEquals(0.8, s4.bootstrap(), TOL);
        assertArrayEquals(new double[] {-0.5, 0, 0, 0}, s3.g(), TOL);
        for (ClosedStep s : steps) {
            assertEquals(CloseType.PREEMPTED, s.closeType());
            assertEquals(5, s.closeCycle());
            assertEquals(0L, s.episodeId());
        }
    }

    @Test
    void resumedEpisodeKeepsItsIdAndItsSegmentsAreNeverMixed() {
        EpisodeBuffer b = new EpisodeBuffer(zeroSetPoint(), GAMMA, 1);
        Collector c = new Collector();
        b.addSink(c);

        b.onFocus(new FocusEvent.Opened(3, HUNGER));
        decide(b, 3, ActionType.APPROACH, HUNGRY, HUNGER, Double.NaN);
        decide(b, 4, ActionType.APPROACH, HUNGRY, HUNGER, Double.NaN);
        b.onFocus(new FocusEvent.Suspended(5, HUNGER, PAIN));
        b.onFocus(new FocusEvent.Opened(5, PAIN));
        decide(b, 5, ActionType.AVOID, PAINFUL, PAIN, Double.NaN);

        // Pain falls below threshold at 6 and hunger resumes in the same update.
        b.onFocus(new FocusEvent.Closed(6, PAIN, FocusEvent.Outcome.REGULATED));
        b.onFocus(new FocusEvent.Resumed(6, HUNGER));
        decide(b, 6, ActionType.EAT, HUNGRY, HUNGER, Double.NaN);
        // Hunger's first segment (close at 5) is written at decision 6; pain's close at 6 at decision 7.
        assertEquals(1, c.calls.size());
        decide(b, 7, ActionType.EAT, HUNGRY, HUNGER, Double.NaN);
        assertEquals(2, c.calls.size());
        b.onOutcome(8, RELIEF);
        b.onFocus(new FocusEvent.Closed(8, HUNGER, FocusEvent.Outcome.REGULATED));
        decide(b, 8, null, NO_K, null, Double.NaN);
        decide(b, 9, null, NO_K, null, Double.NaN);
        assertEquals(3, c.calls.size());

        List<ClosedStep> firstHunger = c.calls.get(0);
        List<ClosedStep> pain = c.calls.get(1);
        List<ClosedStep> secondHunger = c.calls.get(2);

        assertEquals(List.of(3, 4), firstHunger.stream().map(ClosedStep::cycle).toList());
        assertTrue(firstHunger.stream().allMatch(s -> s.closeType() == CloseType.PREEMPTED && s.episodeId() == 0));

        assertEquals(List.of(5), pain.stream().map(ClosedStep::cycle).toList());
        assertEquals(1L, pain.get(0).episodeId());
        assertEquals(CloseType.REGULATED, pain.get(0).closeType());

        // Second segment: steps 6 and 7, same episode id 0, closed by REGULATED at 8.
        // G_7 = 0.5 * -1 = -0.5; G_6 = 0.5 * -0.5 = -0.25. Steps 3 and 4 are not in it.
        assertEquals(List.of(6, 7), secondHunger.stream().map(ClosedStep::cycle).toList());
        assertTrue(secondHunger.stream().allMatch(s -> s.episodeId() == 0 && s.closeCycle() == 8));
        assertArrayEquals(new double[] {-0.25, 0, 0, 0}, at(secondHunger, 6).g(), TOL);
        assertArrayEquals(new double[] {-0.5, 0, 0, 0}, at(secondHunger, 7).g(), TOL);
        assertEquals(2 - 1.75, at(secondHunger, 6).value(), TOL);
    }

    @Test
    void suspendedEpisodeThatTimesOutWritesNothingAgain() {
        EpisodeBuffer b = new EpisodeBuffer(zeroSetPoint(), GAMMA, 1);
        Collector c = new Collector();
        b.addSink(c);

        b.onFocus(new FocusEvent.Opened(3, HUNGER));
        decide(b, 3, ActionType.EAT, HUNGRY, HUNGER, Double.NaN);
        b.onFocus(new FocusEvent.Suspended(4, HUNGER, PAIN));
        b.onFocus(new FocusEvent.Opened(4, PAIN));
        decide(b, 4, ActionType.AVOID, PAINFUL, PAIN, Double.NaN);
        decide(b, 5, ActionType.AVOID, PAINFUL, PAIN, Double.NaN);
        assertEquals(1, c.calls.size(), "the suspended hunger segment is written at its cut");

        // The suspension expires as TIMED_OUT, and pain closes at 6.
        b.onFocus(new FocusEvent.Closed(6, HUNGER, FocusEvent.Outcome.TIMED_OUT));
        b.onFocus(new FocusEvent.Closed(6, PAIN, FocusEvent.Outcome.REGULATED));
        decide(b, 6, null, NO_K, null, Double.NaN);
        decide(b, 7, null, NO_K, null, Double.NaN);

        long hungerSteps = c.all().stream().filter(s -> s.drive().equals(HUNGER)).count();
        assertEquals(1L, hungerSteps, "the hunger step is written exactly once");
        assertEquals(2, c.calls.size(), "one call for the cut hunger segment, one for pain");
    }

    // 3. Timed out: APPROACH steps are zeroed; a consummatory step keeps its Monte Carlo return.
    @Test
    void timedOutEpisodeZeroesOnlyApproachSteps() {
        EpisodeBuffer b = new EpisodeBuffer(zeroSetPoint(), GAMMA, 1);
        Collector c = new Collector();
        b.addSink(c);

        b.onFocus(new FocusEvent.Opened(3, HUNGER));
        decide(b, 3, ActionType.APPROACH, HUNGRY, HUNGER, Double.NaN);
        decide(b, 4, ActionType.EAT, HUNGRY, HUNGER, Double.NaN);
        decide(b, 5, ActionType.APPROACH, HUNGRY, HUNGER, Double.NaN);
        b.onOutcome(4, RELIEF);
        b.onFocus(new FocusEvent.Closed(6, HUNGER, FocusEvent.Outcome.TIMED_OUT));
        decide(b, 6, null, NO_K, null, Double.NaN);
        decide(b, 7, null, NO_K, null, Double.NaN);

        assertEquals(1, c.calls.size());
        List<ClosedStep> steps = c.calls.get(0);
        ClosedStep approach = at(steps, 3);
        ClosedStep eat = at(steps, 4);
        ClosedStep lateApproach = at(steps, 5);
        // Without zeroing, the approach at 3 would be worth 0.5 (G_3 = -0.5).
        assertArrayEquals(new double[4], approach.g(), TOL);
        assertEquals(0, approach.value(), TOL);
        assertArrayEquals(new double[] {-1, 0, 0, 0}, eat.g(), TOL);
        assertEquals(2 - 1, eat.value(), TOL);
        assertEquals(0, lateApproach.value(), TOL);
        for (ClosedStep s : steps) {
            assertEquals(CloseType.TIMED_OUT, s.closeType());
            assertTrue(Double.isNaN(s.bootstrap()));
        }
    }

    // 4. Death: the lethal deviation is the last outcome; open and held segments are written at once.
    @Test
    void deathWritesTheOpenSegmentWithTheLethalDeviationAsItsLastOutcome() {
        EpisodeBuffer b = new EpisodeBuffer(zeroSetPoint(), GAMMA, 1);
        Collector c = new Collector();
        b.addSink(c);

        b.onFocus(new FocusEvent.Opened(3, HUNGER));
        decide(b, 3, ActionType.AVOID, HUNGRY, HUNGER, Double.NaN);
        decide(b, 4, ActionType.AVOID, HUNGRY, HUNGER, Double.NaN);
        double[] lethal = {0, 0, 10, 0};
        b.onDeath(5, lethal);

        assertEquals(1, c.calls.size(), "death writes without waiting for a settle decision");
        List<ClosedStep> steps = c.calls.get(0);
        // G_5 = lethal; G_4 = 0.5 * lethal = (0,0,5,0); G_3 = 0.5 * (0,0,5,0) = (0,0,2.5,0).
        ClosedStep s4 = at(steps, 4);
        ClosedStep s3 = at(steps, 3);
        assertArrayEquals(new double[] {0, 0, 5, 0}, s4.g(), TOL);
        assertArrayEquals(new double[] {0, 0, 2.5, 0}, s3.g(), TOL);
        assertEquals(2 - Math.sqrt(29), s4.value(), TOL);
        assertEquals(2 - Math.sqrt(10.25), s3.value(), TOL);
        assertTrue(s4.value() < 0 && s3.value() < 0, "every buffered step is strongly negative");
        for (ClosedStep s : steps) {
            assertEquals(CloseType.DEATH, s.closeType());
            assertEquals(5, s.closeCycle());
        }
    }

    @Test
    void deathLeavesAlreadyWrittenSuspendedEpisodeAlone() {
        EpisodeBuffer b = new EpisodeBuffer(zeroSetPoint(), GAMMA, 1);
        Collector c = new Collector();
        b.addSink(c);

        b.onFocus(new FocusEvent.Opened(3, HUNGER));
        decide(b, 3, ActionType.AVOID, HUNGRY, HUNGER, Double.NaN);
        b.onFocus(new FocusEvent.Suspended(4, HUNGER, PAIN));
        b.onFocus(new FocusEvent.Opened(4, PAIN));
        decide(b, 4, ActionType.AVOID, PAINFUL, PAIN, Double.NaN);
        b.onDeath(5, new double[] {0, 0, 10, 0});

        assertEquals(2, c.calls.size());
        // The held hunger cut at 4 is written at its own cycle, without the death outcome.
        ClosedStep hunger = c.calls.get(0).get(0);
        assertEquals(CloseType.PREEMPTED, hunger.closeType());
        assertEquals(4, hunger.closeCycle());
        assertArrayEquals(new double[4], hunger.g(), TOL);
        // The open pain segment carries the lethal outcome: G_4 = 0.5 * (0,0,10,0) = (0,0,5,0).
        ClosedStep pain = c.calls.get(1).get(0);
        assertEquals(CloseType.DEATH, pain.closeType());
        assertEquals(1L, pain.episodeId());
        assertArrayEquals(new double[] {0, 0, 5, 0}, pain.g(), TOL);
        assertEquals(3 - 8, pain.value(), TOL);
    }

    // 5. Not queried while open: nothing is written while an episode is open.
    @Test
    void openEpisodeWritesNothingUntilItCloses() {
        EpisodeBuffer b = new EpisodeBuffer(zeroSetPoint(), GAMMA, 1);
        Collector c = new Collector();
        b.addSink(c);

        b.onFocus(new FocusEvent.Opened(3, HUNGER));
        decide(b, 3, ActionType.EAT, HUNGRY, HUNGER, Double.NaN);
        decide(b, 4, ActionType.EAT, HUNGRY, HUNGER, Double.NaN);
        decide(b, 5, ActionType.EAT, HUNGRY, HUNGER, Double.NaN);
        assertTrue(c.calls.isEmpty());
    }

    // 6. Settle rule: an outcome stamped up to the close cycle counts; one stamped later does not.
    @Test
    void lateOutcomeStampedAtTheCloseCycleCountsAndLaterOneDoesNot() {
        EpisodeBuffer b = new EpisodeBuffer(zeroSetPoint(), GAMMA, 1);
        Collector c = new Collector();
        b.addSink(c);

        b.onFocus(new FocusEvent.Opened(3, HUNGER));
        decide(b, 3, ActionType.APPROACH, HUNGRY, HUNGER, Double.NaN);
        decide(b, 4, ActionType.APPROACH, HUNGRY, HUNGER, Double.NaN);
        decide(b, 5, ActionType.APPROACH, HUNGRY, HUNGER, Double.NaN);
        b.onFocus(new FocusEvent.Closed(6, HUNGER, FocusEvent.Outcome.REGULATED));
        // Arrives after the close event, stamped with the close cycle: counted.
        b.onOutcome(6, RELIEF);
        // Stamped after the close cycle: not counted, even though it arrives before the settle decision.
        b.onOutcome(7, new double[] {-10, 0, 0, 0});
        decide(b, 6, null, NO_K, null, Double.NaN);
        decide(b, 7, null, NO_K, null, Double.NaN);

        assertEquals(1, c.calls.size());
        // G_6 = -1 (not a step); G_5 = -0.5; G_4 = -0.25; G_3 = -0.125. The -10 at cycle 7 is absent.
        ClosedStep s5 = at(c.calls.get(0), 5);
        ClosedStep s3 = at(c.calls.get(0), 3);
        assertArrayEquals(new double[] {-0.5, 0, 0, 0}, s5.g(), TOL);
        assertArrayEquals(new double[] {-0.125, 0, 0, 0}, s3.g(), TOL);
        assertEquals(2 - 1.5, s5.value(), TOL);
        assertEquals(2 - 1.875, s3.value(), TOL);
    }

    // 7. No-focus decisions are not buffered.
    @Test
    void decisionsWithoutFocusAreNotBuffered() {
        EpisodeBuffer b = new EpisodeBuffer(zeroSetPoint(), GAMMA, 1);
        Collector c = new Collector();
        b.addSink(c);

        decide(b, 3, ActionType.APPROACH, HUNGRY, null, Double.NaN);
        b.onFocus(new FocusEvent.Opened(4, HUNGER));
        decide(b, 4, ActionType.APPROACH, HUNGRY, HUNGER, Double.NaN);
        b.onFocus(new FocusEvent.Closed(5, HUNGER, FocusEvent.Outcome.REGULATED));
        decide(b, 5, null, NO_K, null, Double.NaN);
        decide(b, 6, null, NO_K, null, Double.NaN);

        assertEquals(1, c.calls.size());
        assertEquals(List.of(4), c.calls.get(0).stream().map(ClosedStep::cycle).toList());
    }

    @Test
    void defaultGammaIsTheTraceHalfLifeAndTheDefaultSettleIsOneCycle() {
        assertEquals(Math.pow(2, -0.2), EpisodeBuffer.DEFAULT_GAMMA, 1e-15);
        assertEquals(1, EpisodeBuffer.DEFAULT_SETTLE_CYCLES);

        EpisodeBuffer b = new EpisodeBuffer(zeroSetPoint());
        Collector c = new Collector();
        b.addSink(c);
        b.onFocus(new FocusEvent.Opened(3, HUNGER));
        decide(b, 3, ActionType.APPROACH, HUNGRY, HUNGER, Double.NaN);
        b.onOutcome(4, RELIEF);
        b.onFocus(new FocusEvent.Closed(4, HUNGER, FocusEvent.Outcome.REGULATED));
        decide(b, 4, null, NO_K, null, Double.NaN);
        assertTrue(c.calls.isEmpty());
        decide(b, 5, null, NO_K, null, Double.NaN);

        // G_3 = gamma * -1 with the default gamma; value = 2 - (2 - gamma) = gamma.
        assertEquals(1, c.calls.size());
        assertEquals(Math.pow(2, -0.2), c.calls.get(0).get(0).value(), TOL);
    }

    // 8. Constructor and input validation.
    @Test
    void constructorAndInputsAreValidated() {
        HomeostaticValue v = zeroSetPoint();
        assertThrows(IllegalArgumentException.class, () -> new EpisodeBuffer(null));
        assertThrows(IllegalArgumentException.class, () -> new EpisodeBuffer(v, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new EpisodeBuffer(v, -0.1, 1));
        assertThrows(IllegalArgumentException.class, () -> new EpisodeBuffer(v, 1.5, 1));
        assertThrows(IllegalArgumentException.class, () -> new EpisodeBuffer(v, Double.NaN, 1));
        assertThrows(IllegalArgumentException.class, () -> new EpisodeBuffer(v, GAMMA, 0));
        assertThrows(IllegalArgumentException.class, () -> new EpisodeBuffer(v, GAMMA, -1));
        assertThrows(IllegalArgumentException.class, () -> new EpisodeBuffer(v, 1, 1).addSink(null));
        // gamma = 1 (no discount) and any settle >= 1 are accepted.
        new EpisodeBuffer(v, 1, 1);
        new EpisodeBuffer(v, GAMMA, 3);

        EpisodeBuffer b = new EpisodeBuffer(v, GAMMA, 1);
        assertThrows(IllegalArgumentException.class, () -> b.onOutcome(3, new double[] {1, 0, 0}));
        assertThrows(IllegalArgumentException.class, () -> b.onOutcome(3, new double[] {Double.NaN, 0, 0, 0}));
        assertThrows(IllegalArgumentException.class, () -> b.onDeath(3, new double[] {0, Double.POSITIVE_INFINITY, 0, 0}));
        assertThrows(IllegalArgumentException.class, () -> decide2(b, null));
        assertThrows(IllegalArgumentException.class,
                () -> b.onDecision(3, act(ActionType.EAT, 1), HUNGRY, null, Double.POSITIVE_INFINITY));
        assertThrows(IllegalArgumentException.class,
                () -> b.onDecision(3, act(ActionType.EAT, 1), new double[] {1, 2}, null, Double.NaN));
    }

    private static void decide2(EpisodeBuffer b, Action action) {
        b.onDecision(3, action, HUNGRY, null, Double.NaN);
    }

    @Test
    void stateMachineInvariantViolationsAreReported() {
        EpisodeBuffer b = new EpisodeBuffer(zeroSetPoint(), GAMMA, 1);
        b.onFocus(new FocusEvent.Opened(3, HUNGER));
        assertThrows(IllegalStateException.class, () -> b.onFocus(new FocusEvent.Opened(4, PAIN)));
        assertThrows(IllegalStateException.class, () -> b.onFocus(new FocusEvent.Suspended(4, PAIN, HUNGER)));
        assertThrows(IllegalStateException.class, () -> b.onFocus(new FocusEvent.Resumed(4, PAIN)));
    }

    // Review follow-up: the K of an episode's first act can arrive before the Opened event is processed.
    @Test
    void outcomeArrivingBeforeOpenedStillCountsForTheFirstStep() {
        EpisodeBuffer b = new EpisodeBuffer(zeroSetPoint(), GAMMA, 1);
        Collector c = new Collector();
        b.addSink(c);

        b.onOutcome(3, RELIEF);
        b.onFocus(new FocusEvent.Opened(3, HUNGER));
        decide(b, 3, ActionType.EAT, HUNGRY, HUNGER, Double.NaN);
        b.onFocus(new FocusEvent.Closed(4, HUNGER, FocusEvent.Outcome.REGULATED));
        decide(b, 5, ActionType.WANDER, NO_K, null, Double.NaN);

        assertEquals(1.0, at(c.all(), 3).value(), TOL);
    }

    @Test
    void oldOutcomesWithNoLiveSegmentAreEventuallyDropped() {
        EpisodeBuffer b = new EpisodeBuffer(zeroSetPoint(), GAMMA, 1);
        Collector c = new Collector();
        b.addSink(c);

        b.onOutcome(1, RELIEF);
        decide(b, 10, ActionType.WANDER, NO_K, null, Double.NaN);
        b.onFocus(new FocusEvent.Opened(10, HUNGER));
        decide(b, 10, ActionType.EAT, HUNGRY, HUNGER, Double.NaN);
        b.onFocus(new FocusEvent.Closed(11, HUNGER, FocusEvent.Outcome.REGULATED));
        decide(b, 12, ActionType.WANDER, NO_K, null, Double.NaN);

        assertEquals(0.0, at(c.all(), 10).value(), TOL);
    }

    // Review follow-up: a sink that throws must not starve the sinks after it.
    @Test
    void failingSinkDoesNotStopTheNextSink() {
        EpisodeBuffer b = new EpisodeBuffer(zeroSetPoint(), GAMMA, 1);
        Collector c = new Collector();
        b.addSink(steps -> { throw new IllegalStateException("boom"); });
        b.addSink(c);

        b.onFocus(new FocusEvent.Opened(3, HUNGER));
        decide(b, 3, ActionType.EAT, HUNGRY, HUNGER, Double.NaN);
        b.onFocus(new FocusEvent.Closed(4, HUNGER, FocusEvent.Outcome.REGULATED));
        decide(b, 5, ActionType.WANDER, NO_K, null, Double.NaN);

        assertEquals(1, c.all().size());
    }
}
