package br.cefetmg.lsi.l2l.creature.bd;

import org.apache.arrow.vector.compression.CompressionUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** {@link OperantUpdateState} rows written through {@link ArrowIpcBackend} read back with the values written. */
class OperantUpdateRoundTripTest {

    @Test
    void operantUpdateRowsRoundTrip(@TempDir Path tmp) throws Exception {
        ArrowIpcBackend backend = new ArrowIpcBackend(tmp, null, 256, ArrowIpcBackend.DEFAULT_BATCH_ROWS,
                CompressionUtil.CodecType.NO_COMPRESSION);
        try {
            backend.persistBatch(Map.of("operant_update_state", List.of(
                    new OperantUpdateState(7L, 0L, 1000L, 4L, "RED_APPLE", "EAT", 1.5, 1.0, 0.75, 0.5, 1.0),
                    new OperantUpdateState(7L, 1L, 2000L, 9L, "GREEN_APPLE", "AVOID", -0.5, 1.995, -0.3, 0.4, 0.8))));
            backend.dumpToParquet();

            Map<String, List<Object>> cols = ArrowTestReader.readArrowFile(tmp.resolve("operant_update_state.arrow"));
            assertEquals(2, ArrowTestReader.rowCount(cols));
            assertEquals(List.of(7L, 7L), cols.get("creature_key"));
            assertEquals(List.of(0L, 1L), cols.get("seq"));
            assertEquals(List.of(1000L, 2000L), cols.get("time_ms"));
            assertEquals(List.of(4L, 9L), cols.get("cycle"));
            assertEquals(List.of("RED_APPLE", "GREEN_APPLE"), cols.get("object_type"));
            assertEquals(List.of("EAT", "AVOID"), cols.get("action"));
            assertEquals(List.of(1.5, -0.5), cols.get("sample"));
            assertEquals(List.of(1.0, 1.995), cols.get("n"));
            assertEquals(List.of(0.75, -0.3), cols.get("q"));
            assertEquals(List.of(0.5, 0.4), cols.get("se"));
            assertEquals(List.of(1.0, 0.8), cols.get("sigma"));
        } finally {
            backend.close();
        }
    }
}
