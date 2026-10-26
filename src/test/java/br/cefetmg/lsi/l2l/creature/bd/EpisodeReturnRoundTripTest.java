package br.cefetmg.lsi.l2l.creature.bd;

import org.apache.arrow.vector.compression.CompressionUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Issue #110: {@link EpisodeReturnState} rows written through {@link ArrowIpcBackend} read back with the
 * values they were written with. The bootstrap column is null for a step that was not pre-empted.
 */
class EpisodeReturnRoundTripTest {

    private static final long DEFAULT_LIMIT_MB = 256;

    @Test
    void episodeReturnRowsRoundTripWithTheirReturnsAndANullBootstrapWhenNotPreempted(@TempDir Path tmp) throws Exception {
        ArrowIpcBackend backend = new ArrowIpcBackend(tmp, null, DEFAULT_LIMIT_MB, ArrowIpcBackend.DEFAULT_BATCH_ROWS,
                CompressionUtil.CodecType.NO_COMPRESSION);
        try {
            // Regulated: G = (-1, 0, 0, 0) at decision, value = 1.0, no bootstrap.
            EpisodeReturnState regulated = new EpisodeReturnState(7L, 0L, 1000L, 3L, "hunger", "REGULATED",
                    4L, 2L, "EAT", "GREEN_APPLE", 12.5, new double[] {2, 0, 0, 0}, new double[] {-1, 0, 0, 0},
                    Double.NaN, 1.0);
            // Pre-empted at 5 with B = 0.8: value = V + gamma^(5-4) * B = 0.2.
            EpisodeReturnState preempted = new EpisodeReturnState(7L, 1L, 2000L, 4L, "pain", "PREEMPTED",
                    4L, 1L, "AVOID", null, 0.0, new double[] {0, 0, 3, 0}, new double[] {0, 0, 0, 0},
                    0.8, 0.2);

            backend.persistBatch(Map.of("episode_return", List.of(regulated, preempted)));
            backend.dumpToParquet();

            Map<String, List<Object>> cols = ArrowTestReader.readArrowFile(tmp.resolve("episode_return.arrow"));
            assertEquals(2, ArrowTestReader.rowCount(cols));
            assertEquals(List.of(7L, 7L), cols.get("creature_key"));
            assertEquals(List.of(0L, 1L), cols.get("seq"));
            assertEquals(List.of(3L, 4L), cols.get("episode_id"));
            assertEquals(List.of("hunger", "pain"), cols.get("drive"));
            assertEquals(List.of("REGULATED", "PREEMPTED"), cols.get("close_type"));
            assertEquals(List.of(4L, 4L), cols.get("step_cycle"));
            assertEquals(List.of(2L, 1L), cols.get("steps_to_close"));
            assertEquals(List.of("EAT", "AVOID"), cols.get("action"));
            assertEquals("GREEN_APPLE", cols.get("object_type").get(0));
            assertNull(cols.get("object_type").get(1));
            assertEquals(List.of(12.5, 0.0), cols.get("distance"));
            assertEquals(List.of(2.0, 0.0), cols.get("h_hunger"));
            assertEquals(List.of(0.0, 3.0), cols.get("h_pain"));
            assertEquals(List.of(-1.0, 0.0), cols.get("g_hunger"));
            assertEquals(List.of(0.0, 0.0), cols.get("g_pain"));
            assertNull(cols.get("bootstrap").get(0), "a regulated step has no bootstrap");
            assertEquals(0.8, (Double) cols.get("bootstrap").get(1), 0.0);
            assertEquals(List.of(1.0, 0.2), cols.get("value"));
        } finally {
            backend.close();
        }
    }
}
