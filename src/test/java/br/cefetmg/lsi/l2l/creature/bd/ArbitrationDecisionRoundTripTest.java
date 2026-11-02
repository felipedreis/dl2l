package br.cefetmg.lsi.l2l.creature.bd;

import br.cefetmg.lsi.l2l.common.Constants;
import br.cefetmg.lsi.l2l.common.SequentialId;
import br.cefetmg.lsi.l2l.creature.arbiter.Arbiter;
import br.cefetmg.lsi.l2l.creature.arbiter.Arbiter.Controller;
import br.cefetmg.lsi.l2l.creature.arbiter.Arbiter.ControllerType;
import br.cefetmg.lsi.l2l.creature.arbiter.Arbiter.Params;
import br.cefetmg.lsi.l2l.creature.arbiter.ArbitrationDecision;
import br.cefetmg.lsi.l2l.creature.arbiter.ArbitrationDecision.ExplorationSource;
import br.cefetmg.lsi.l2l.creature.arbiter.ExecutedEstimate;
import br.cefetmg.lsi.l2l.creature.arbiter.OperantEstimator;
import br.cefetmg.lsi.l2l.creature.common.Action;
import br.cefetmg.lsi.l2l.creature.common.ActionType;
import br.cefetmg.lsi.l2l.creature.common.Perception;
import br.cefetmg.lsi.l2l.world.FruitType;
import org.apache.arrow.vector.compression.CompressionUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Issue #105: {@link ArbitrationDecisionState} rows, built from real {@link Arbiter} decisions, written through
 * {@link ArrowIpcBackend} and read back. Covers a Thompson pick executed as chosen, a single-candidate cycle, and
 * a gate override (executed is another candidate). Null rules: runner-up null for one candidate, the
 * controllers not consulted, and a missing focal drive; no NaN is ever stored.
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class ArbitrationDecisionRoundTripTest {

    private static final long DEFAULT_LIMIT_MB = 256;
    private static final double[] H = {0.0, 0.0, 0.0, 0.0};
    private static final String HUNGER = Constants.HUNGER;
    private static final Perception APPLE = new Perception(FruitType.RED_APPLE, new SequentialId(1), 10, 0);

    private static Action on(ActionType type) {
        return new Action(type, APPLE);
    }

    /** EAT Q = 1 and AVOID Q = 0, one controller, so the stopping rule is trivially decided by OPERANT. */
    private static OperantEstimator operant() {
        OperantEstimator e = new OperantEstimator(new double[] {50, 100}, 1.0);
        for (int i = 0; i < 20; i++) {
            e.record(HUNGER, on(ActionType.EAT), 2.0);
            e.record(HUNGER, on(ActionType.AVOID), 0.0);
        }
        return e;
    }

    private static ArbitrationDecisionState row(long cycle, String focal, ArbitrationDecision d, ExecutedEstimate x) {
        return ArbitrationDecisionState.of(7L, cycle - 1, 1000L + cycle, cycle, focal, 0.25, d, x);
    }

    @Test
    void arbitrationDecisionRowsRoundTripWithTheirEstimatesAndNullsWhereAbsent(@TempDir Path tmp) throws Exception {
        OperantEstimator e = operant();
        List<Action> two = List.of(on(ActionType.EAT), on(ActionType.AVOID));

        // A Thompson pick (seed chosen so the draw is not the best): executed as chosen.
        // A close pair (Q 0.5 vs 0, one observation each) so the Thompson draw picks the lower one often.
        OperantEstimator close = new OperantEstimator(new double[] {50, 100}, 1.0);
        close.record(HUNGER, on(ActionType.EAT), 1.0);
        close.record(HUNGER, on(ActionType.AVOID), 0.0);
        // Seed 5 was found once (a scan outside the test) to give a THOMPSON pick on this fixture.
        ArbitrationDecision thompson = new Arbiter(List.of(new Controller(ControllerType.OPERANT, close)),
                new Params(0.8, 0.0, 1.0), new Random(5L)).decide(two, H, HUNGER);
        assertEquals(ExplorationSource.THOMPSON, thompson.source(), "seed 5 must give a THOMPSON pick");
        ArbitrationDecisionState first = row(1, HUNGER, thompson,
                ExecutedEstimate.of(thompson, thompson.chosen(), a -> null));

        // One candidate: nothing to choose among, no runner-up, no focal drive.
        ArbitrationDecision single = new Arbiter(List.of(new Controller(ControllerType.OPERANT, e)),
                Params.defaults(), new Random(2)).decide(List.of(on(ActionType.EAT)), H, null);
        ArbitrationDecisionState second = row(2, null, single,
                ExecutedEstimate.of(single, single.chosen(), a -> null));

        // Greedy over both candidates; the gate replaced the pick with the other candidate.
        ArbitrationDecision greedy = new Arbiter(List.of(new Controller(ControllerType.OPERANT, e)),
                new Params(0.8, 0.0, 0.0), new Random(3)).decide(two, H, HUNGER);
        Action gated = greedy.candidates().get(1);
        ArbitrationDecisionState third = row(3, HUNGER, greedy,
                ExecutedEstimate.of(greedy, gated, a -> null));

        ArrowIpcBackend backend = new ArrowIpcBackend(tmp, null, DEFAULT_LIMIT_MB, ArrowIpcBackend.DEFAULT_BATCH_ROWS,
                CompressionUtil.CodecType.NO_COMPRESSION);
        try {
            backend.persistBatch(Map.of("arbitration_decision_state", List.of(first, second, third)));
            backend.dumpToParquet();
        } finally {
            backend.close();
        }

        Map<String, List<Object>> cols = ArrowTestReader.readArrowFile(tmp.resolve("arbitration_decision_state.arrow"));
        assertEquals(3, ArrowTestReader.rowCount(cols));
        assertEquals(List.of(7L, 7L, 7L), cols.get("creature_key"));
        assertEquals(List.of(0L, 1L, 2L), cols.get("seq"));
        assertEquals(List.of(1L, 2L, 3L), cols.get("cycle"));

        assertEquals(HUNGER, cols.get("focal_drive").get(0));
        assertNull(cols.get("focal_drive").get(1), "no open episode, no focal drive");
        assertEquals(HUNGER, cols.get("focal_drive").get(2));

        assertEquals(List.of(thompson.source().name(), "SINGLE", greedy.source().name()), cols.get("source"));
        assertEquals(List.of("OPERANT", "OPERANT", "OPERANT"), cols.get("affordable_controller"));
        assertEquals(List.of("OPERANT", "OPERANT", "OPERANT"), cols.get("deciding_controller"));

        assertEquals(List.of("CHOSEN", "CHOSEN", "OTHER_CANDIDATE"), cols.get("executed_source"));
        assertEquals(gated.type.name(), cols.get("executed_action").get(2));
        assertEquals(thompson.chosen().type.name(), cols.get("chosen_action").get(0));

        assertEquals(thompson.runnerUpEstimate().q(), (Double) cols.get("runnerup_q").get(0), 0.0);
        assertNull(cols.get("runnerup_q").get(1), "one candidate has no runner-up");
        assertNull(cols.get("runnerup_se").get(1));
        assertNull(cols.get("runnerup_sigma").get(1));

        assertEquals(greedy.chosenEstimate().q(), (Double) cols.get("chosen_q").get(2), 0.0);
        assertEquals(greedy.bestEstimate().q(), (Double) cols.get("best_q").get(2), 0.0);
        assertEquals(thompson.chosenEstimate().q(), (Double) cols.get("executed_q").get(0), 0.0);
        assertEquals(greedy.estimates().get(1).q(), (Double) cols.get("executed_q").get(2), 0.0);

        assertTrue(cols.get("operant_conf").stream().allMatch(v -> v != null), "the one controller was consulted");
        // The operant block of the first row is the controller's estimate of the executed (= chosen) action.
        assertEquals(thompson.consultations().get(0).conf(), (Double) cols.get("operant_conf").get(0), 0.0);
        assertEquals(thompson.chosenEstimate().q(), (Double) cols.get("operant_q").get(0), 0.0);
        assertEquals(thompson.chosenEstimate().se(), (Double) cols.get("operant_se").get(0), 0.0);
        assertEquals(thompson.chosenEstimate().sigma(), (Double) cols.get("operant_sigma").get(0), 0.0);
        assertNull(cols.get("episodic_conf").get(0), "not consulted");
        assertNull(cols.get("planner_q").get(0), "not consulted");
        assertNull(cols.get("episodic_q").get(2));

        assertEquals(thompson.chosen().getTarget().name(), cols.get("chosen_object_type").get(0));

        for (Map.Entry<String, List<Object>> col : cols.entrySet()) {
            for (Object v : col.getValue()) {
                assertFalse(v instanceof Double d && d.isNaN(), "no NaN in column " + col.getKey());
            }
        }
    }
}
