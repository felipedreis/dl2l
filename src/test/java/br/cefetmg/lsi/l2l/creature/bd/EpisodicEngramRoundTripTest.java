package br.cefetmg.lsi.l2l.creature.bd;

import br.cefetmg.lsi.l2l.creature.arbiter.ClosedStep;
import br.cefetmg.lsi.l2l.creature.arbiter.EngramKeyEncoder;
import br.cefetmg.lsi.l2l.creature.arbiter.EpisodicEngram;
import br.cefetmg.lsi.l2l.creature.common.ActionType;
import org.apache.arrow.vector.compression.CompressionUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Issue #111: {@link EpisodicEngramState} rows round-trip through {@link ArrowIpcBackend}, key included. */
class EpisodicEngramRoundTripTest {

    @Test
    void episodicEngramRowsRoundTripWithTheirKeyAndReturn(@TempDir Path tmp) throws Exception {
        ArrowIpcBackend backend = new ArrowIpcBackend(tmp, null, 256, ArrowIpcBackend.DEFAULT_BATCH_ROWS,
                CompressionUtil.CodecType.NO_COMPRESSION);
        try {
            double[] key = new double[EngramKeyEncoder.KEY_DIM];
            for (int i = 0; i < key.length; i++) {
                key[i] = i / 10.0;
            }
            EpisodicEngram e = new EpisodicEngram(ActionType.EAT, "GREEN_APPLE", key, new double[]{2, 0.5, 0.2, 0.3},
                    new double[]{-1, 0, 0, 0.1}, 0.25, 9, "hunger", ClosedStep.CloseType.PREEMPTED, 40, 3);
            EpisodicEngram none = new EpisodicEngram(ActionType.WANDER, null, key, new double[]{0, 0, 0, 0},
                    new double[4], 0, 9, "tedium", ClosedStep.CloseType.REGULATED, 41, 1);
            backend.persistBatch(Map.of("episodic_engram", List.of(
                    new EpisodicEngramState(7L, 0L, 1000L, e), new EpisodicEngramState(7L, 1L, 1001L, none))));
            backend.dumpToParquet();

            Map<String, List<Object>> cols = ArrowTestReader.readArrowFile(tmp.resolve("episodic_engram.arrow"));
            assertEquals(2, ArrowTestReader.rowCount(cols));
            assertEquals(List.of(7L, 7L), cols.get("creature_key"));
            assertEquals(List.of(0L, 1L), cols.get("seq"));
            assertEquals(List.of(9L, 9L), cols.get("episode_id"));
            assertEquals(List.of("hunger", "tedium"), cols.get("drive"));
            assertEquals(List.of("PREEMPTED", "REGULATED"), cols.get("close_type"));
            assertEquals(List.of(40L, 41L), cols.get("step_cycle"));
            assertEquals(List.of(3L, 1L), cols.get("steps_to_outcome"));
            assertEquals(List.of("EAT", "WANDER"), cols.get("action"));
            assertEquals("GREEN_APPLE", cols.get("object_type").get(0));
            assertNull(cols.get("object_type").get(1));
            assertEquals(List.of(2.0, 0.0), cols.get("h_hunger"));
            assertEquals(List.of(-1.0, 0.0), cols.get("g_hunger"));
            assertEquals(List.of(0.1, 0.0), cols.get("g_tedium"));
            assertEquals(List.of(0.25, 0.0), cols.get("bootstrap_term"));
            for (int i = 0; i < EngramKeyEncoder.KEY_DIM; i++) {
                assertEquals(i / 10.0, (Double) cols.get(String.format("k_%02d", i)).get(0), 0.0, "k_" + i);
            }
        } finally {
            backend.close();
        }
    }
}
