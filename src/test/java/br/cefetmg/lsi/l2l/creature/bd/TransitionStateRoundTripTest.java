package br.cefetmg.lsi.l2l.creature.bd;

import br.cefetmg.lsi.l2l.common.SequentialId;
import org.apache.arrow.vector.compression.CompressionUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Plan A.3: {@link DecisionStepState}, {@link CycleOutcomeState} and {@link DecisionPerceptState} rows written through
 * {@link ArrowIpcBackend} and read back, with the nulls the tables promise (no target, no focal drive, no object).
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class TransitionStateRoundTripTest {

    private static final long DEFAULT_LIMIT_MB = 256;

    @Test
    void transitionRowsRoundTripWithTheirNullsAndCycles(@TempDir Path tmp) throws Exception {
        SequentialId target = new SequentialId(42L);
        DecisionStepState eat = new DecisionStepState(7L, 4L, "EAT", "RED_APPLE", target,
                new double[] {3.0, 0.5, 0.0, 0.25}, "HUNGER");
        DecisionStepState wander = new DecisionStepState(7L, 5L, "WANDER", null, null,
                new double[] {2.5, 0.0, 0.0, 0.0}, null);
        CycleOutcomeState outcome = new CycleOutcomeState(7L, 4L, new double[] {-1.5, 0.0, 0.25, 0.0});
        DecisionPerceptState near = new DecisionPerceptState(7L, 4L, 0, 42L, 43L, "RED_APPLE", 10.0, 0.0, true);
        DecisionPerceptState far = new DecisionPerceptState(7L, 4L, 1, null, null, null, 120.0, -0.5, false);

        ArrowIpcBackend backend = new ArrowIpcBackend(tmp, null, DEFAULT_LIMIT_MB, ArrowIpcBackend.DEFAULT_BATCH_ROWS,
                CompressionUtil.CodecType.NO_COMPRESSION);
        try {
            backend.persistBatch(Map.of(
                    "decision_steps", List.of(eat, wander),
                    "cycle_outcomes", List.of(outcome),
                    "decision_percepts", List.of(near, far)));
            backend.dumpToParquet();
        } finally {
            backend.close();
        }

        Map<String, List<Object>> steps = ArrowTestReader.readArrowFile(tmp.resolve("decision_steps.arrow"));
        assertEquals(List.of(7L, 7L), steps.get("creature_key"));
        assertEquals(List.of(4L, 5L), steps.get("cycle"));
        assertEquals(List.of("EAT", "WANDER"), steps.get("action"));
        assertEquals("RED_APPLE", steps.get("object_type").get(0));
        assertNull(steps.get("object_type").get(1));
        assertEquals(42L, steps.get("target_key").get(0));
        assertEquals(target.sequential, steps.get("target_sequential").get(0));
        assertNull(steps.get("target_key").get(1), "no target");
        assertEquals(0.25, (Double) steps.get("h_tedium").get(0), 0.0);
        assertEquals(2.5, (Double) steps.get("h_hunger").get(1), 0.0);
        assertEquals("HUNGER", steps.get("focal_drive").get(0));
        assertNull(steps.get("focal_drive").get(1), "no open episode");

        Map<String, List<Object>> outcomes = ArrowTestReader.readArrowFile(tmp.resolve("cycle_outcomes.arrow"));
        assertEquals(List.of(4L), outcomes.get("cycle"));
        assertEquals(-1.5, (Double) outcomes.get("d_hunger").get(0), 0.0);
        assertEquals(0.25, (Double) outcomes.get("d_pain").get(0), 0.0);

        Map<String, List<Object>> percepts = ArrowTestReader.readArrowFile(tmp.resolve("decision_percepts.arrow"));
        assertEquals(List.of(4L, 4L), percepts.get("cycle"));
        assertEquals(List.of(0, 1), percepts.get("rank"));
        assertEquals(List.of(true, false), percepts.get("contact"));
        assertEquals(42L, percepts.get("object_key").get(0));
        assertNull(percepts.get("object_key").get(1), "no object id");
        assertNull(percepts.get("object_type").get(1));
        assertEquals(-0.5, (Double) percepts.get("angle").get(1), 0.0);
    }
}
