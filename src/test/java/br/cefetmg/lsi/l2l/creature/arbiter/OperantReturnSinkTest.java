package br.cefetmg.lsi.l2l.creature.arbiter;

import br.cefetmg.lsi.l2l.common.SequentialId;
import br.cefetmg.lsi.l2l.creature.common.Action;
import br.cefetmg.lsi.l2l.creature.common.ActionType;
import br.cefetmg.lsi.l2l.creature.common.Perception;
import br.cefetmg.lsi.l2l.world.FruitType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link OperantReturnSink} writing closed episodes into the real {@link OperantEstimator}.
 */
class OperantReturnSinkTest {

    private static final String HUNGER = "hunger";
    private static final String SLEEP = "sleep";
    private static final double[] HUNGRY = {2, 0, 0, 0};
    private static final double[] NO_K = {0, 0, 0, 0};
    private static final double[] ZERO_H = {0, 0, 0, 0};
    private static final double TOL = 1e-9;

    @Test
    void closedEpisodeIsWrittenIntoTheCellItWasDecidedInAndReadBackByTheEstimator() {
        String[] focal = {HUNGER};
        OperantEstimator estimator = new OperantEstimator(() -> focal[0]);
        EpisodeBuffer buffer = new EpisodeBuffer(
                new HomeostaticValue(new double[4], new double[] {1, 1, 1, 1}), 0.5, 1);
        buffer.addSink(new OperantReturnSink(estimator));

        Action eat = new Action(ActionType.EAT, new Perception(FruitType.GREEN_APPLE, new SequentialId(7), 10, 0));
        buffer.onFocus(new FocusEvent.Opened(3, HUNGER));
        buffer.onDecision(3, eat, HUNGRY, HUNGER, Double.NaN);
        buffer.onOutcome(4, new double[] {-1, 0, 0, 0});
        buffer.onFocus(new FocusEvent.Closed(4, HUNGER, FocusEvent.Outcome.REGULATED));
        buffer.onDecision(4, eat, NO_K, null, Double.NaN);
        buffer.onDecision(5, eat, NO_K, null, Double.NaN);

        // G_3 = 0.5 * -1 = -0.5, value = 2 - (2 - 0.5) = 0.5, so Q = 0.5 / (1 + 1) = 0.25 in the EAT cell.
        var estimate = estimator.estimate(List.of(eat), ZERO_H).get(0);
        assertEquals(0.25, estimate.q(), TOL);
        assertEquals(1 / Math.sqrt(2), estimate.se(), TOL);
    }

    @Test
    void recordedValueMovesTheEstimateOfItsCellOnly() {
        String[] focal = {HUNGER};
        OperantEstimator estimator = new OperantEstimator(() -> focal[0]);
        Action eat = new Action(ActionType.EAT, new Perception(FruitType.GREEN_APPLE, new SequentialId(7), 10, 0));
        assertEquals(0, estimator.estimate(List.of(eat), ZERO_H).get(0).q(), TOL);

        // Value 0.5 for one observation: Q = (1 * 0.5 + 0) / (1 + 1) = 0.25; SE = sigma / sqrt(1 + 1)
        // with sigma = SIGMA0 = 1 while n <= 1.
        new OperantReturnSink(estimator).onClosed(List.of(stepFor(HUNGER, eat, 0.5)));
        var written = estimator.estimate(List.of(eat), ZERO_H).get(0);
        assertEquals(0.25, written.q(), TOL);
        assertEquals(1 / Math.sqrt(2), written.se(), TOL);

        // The same action under another focal drive is another cell: still unseen.
        focal[0] = SLEEP;
        var other = estimator.estimate(List.of(eat), ZERO_H).get(0);
        assertEquals(0, other.q(), TOL);
        assertEquals(1, other.se(), TOL);
    }

    @Test
    void sinkRejectsANullLearner() {
        assertThrows(IllegalArgumentException.class, () -> new OperantReturnSink(null));
    }

    private static ClosedStep stepFor(String drive, Action action, double value) {
        return new ClosedStep(0L, drive, ClosedStep.CloseType.REGULATED, 3, 4, action,
                HUNGRY, NO_K, Double.NaN, value);
    }
}
