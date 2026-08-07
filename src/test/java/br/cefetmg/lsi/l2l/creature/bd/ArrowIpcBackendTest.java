package br.cefetmg.lsi.l2l.creature.bd;

import br.cefetmg.lsi.l2l.common.SequentialId;
import org.apache.arrow.memory.OutOfMemoryException;
import org.apache.arrow.vector.compression.CompressionUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ArrowIpcBackend} - round trip, null handling, batch-boundary flushing,
 * post-finalize behavior, and allocator-limit enforcement. See
 * docs/plans/arrow-ipc-write-path.md, W2/PR1-verification item 1.
 *
 * <p>Uses the package-private full constructor to control {@code batchRows}/allocator limit
 * directly rather than through env vars, and passes a {@code null} {@code MetricsExtension.Impl}
 * throughout - {@link ArrowIpcBackend} null-checks it everywhere, and standing up a real one
 * means an Akka {@code ActorSystem} plus an HTTP server, unnecessary for these tests.
 */
class ArrowIpcBackendTest {

    private static final long DEFAULT_LIMIT_MB = 256;

    @Test
    void constructorCreatesAllTwentyTwoRawFilesUpFront(@TempDir Path tmp) throws Exception {
        ArrowIpcBackend backend = new ArrowIpcBackend(tmp, null, DEFAULT_LIMIT_MB, ArrowIpcBackend.DEFAULT_BATCH_ROWS,
                CompressionUtil.CodecType.NO_COMPRESSION);
        try {
            for (TableSchemas.TableDef<?> def : TableSchemas.ALL) {
                assertTrue(Files.exists(tmp.resolve(def.table() + ".arrow")), "missing file for " + def.table());
            }
        } finally {
            backend.close();
        }
    }

    @Test
    void roundTripsWrittenRowsWithCorrectValues(@TempDir Path tmp) throws Exception {
        ArrowIpcBackend backend = new ArrowIpcBackend(tmp, null, DEFAULT_LIMIT_MB, ArrowIpcBackend.DEFAULT_BATCH_ROWS,
                CompressionUtil.CodecType.NO_COMPRESSION);
        try {
            CreatureState alive = new CreatureState(new SequentialId(1), true);
            alive.setBornTime(1000L);
            CreatureState dead = new CreatureState(new SequentialId(2), false);
            dead.setBornTime(2000L);
            dead.setDeadTime(5000L);

            backend.persistBatch(Map.of("creature_state", List.of(alive, dead)));
            backend.dumpToParquet();

            Map<String, List<Object>> cols = ArrowTestReader.readArrowFile(tmp.resolve("creature_state.arrow"));
            assertEquals(2, ArrowTestReader.rowCount(cols));
            assertEquals(List.of(alive.getId().toString(), dead.getId().toString()), cols.get("id"));
            assertEquals(List.of(1000L, 2000L), cols.get("borntime"));
            assertEquals(List.of(0L, 5000L), cols.get("deadtime"));
            assertEquals(List.of(true, false), cols.get("gender"));
        } finally {
            backend.close();
        }
    }

    @Test
    void allNullOptionalColumnsRoundTripAsNullNotAsAnExceptionOrSentinel(@TempDir Path tmp) throws Exception {
        // StimulusState's every non-id column is nullable and left unset here - this is
        // exactly the shape that crashed parquet-floor's writeIfPresent (see ArrowIpcBackend's
        // javadoc) - Arrow's unset-slot-is-null semantics must not require any workaround.
        ArrowIpcBackend backend = new ArrowIpcBackend(tmp, null, DEFAULT_LIMIT_MB, ArrowIpcBackend.DEFAULT_BATCH_ROWS,
                CompressionUtil.CodecType.NO_COMPRESSION);
        try {
            StimulusState blank = new StimulusState();
            backend.persistBatch(Map.of("stimulus_state", List.of(blank)));
            backend.dumpToParquet();

            Map<String, List<Object>> cols = ArrowTestReader.readArrowFile(tmp.resolve("stimulus_state.arrow"));
            assertEquals(1, ArrowTestReader.rowCount(cols));
            assertEquals(blank.getId().toString(), cols.get("id").get(0));
            for (String nullable : List.of("stimulusclass", "type", "componentkey", "stimulusseq",
                    "changestimulusemitted_id", "changestimulusreceived_id")) {
                assertNull(cols.get(nullable).get(0), nullable + " must round-trip as null");
            }
        } finally {
            backend.close();
        }
    }

    @Test
    void batchBoundaryFlushesExactlyAtThresholdLeavingTheNPlus1thRowUnflushedUntilFinalize(@TempDir Path tmp) throws Exception {
        int batchRows = 2;
        ArrowIpcBackend backend = new ArrowIpcBackend(tmp, null, DEFAULT_LIMIT_MB, batchRows,
                CompressionUtil.CodecType.NO_COMPRESSION);
        try {
            Path file = tmp.resolve("nose_state.arrow");

            backend.persistBatch(Map.of("nose_state", List.of(new NoseState(), new NoseState())));
            // Exactly at the batch-size threshold: write() must have flushed synchronously,
            // readable even though the stream hasn't been end()-ed/finalized yet.
            assertEquals(2, ArrowTestReader.rowCount(ArrowTestReader.readArrowFile(file)), "N==batchRows must already be flushed to disk");

            backend.persistBatch(Map.of("nose_state", List.of(new NoseState())));
            // N+1: the 3rd row starts a new, not-yet-full batch - must NOT be visible yet.
            assertEquals(2, ArrowTestReader.rowCount(ArrowTestReader.readArrowFile(file)), "the N+1th row must stay unflushed until threshold or finalize");

            backend.dumpToParquet();
            assertEquals(3, ArrowTestReader.rowCount(ArrowTestReader.readArrowFile(file)), "finalize must flush the trailing partial batch");
        } finally {
            backend.close();
        }
    }

    @Test
    void postFinalizeWritesAreDroppedNotCrashingOrCorruptingTheFile(@TempDir Path tmp) throws Exception {
        ArrowIpcBackend backend = new ArrowIpcBackend(tmp, null, DEFAULT_LIMIT_MB, ArrowIpcBackend.DEFAULT_BATCH_ROWS,
                CompressionUtil.CodecType.NO_COMPRESSION);
        Path file = tmp.resolve("nose_state.arrow");
        backend.persistBatch(Map.of("nose_state", List.of(new NoseState())));
        backend.dumpToParquet(); // finalizes every writer

        assertDoesNotThrow(() -> backend.persistBatch(Map.of("nose_state", List.of(new NoseState(), new NoseState()))));

        assertEquals(1, ArrowTestReader.rowCount(ArrowTestReader.readArrowFile(file)), "post-finalize writes must be dropped, not appended");

        backend.close(); // idempotent, must not throw a second time
    }

    @Test
    void dumpToParquetIsIdempotent(@TempDir Path tmp) throws Exception {
        ArrowIpcBackend backend = new ArrowIpcBackend(tmp, null, DEFAULT_LIMIT_MB, ArrowIpcBackend.DEFAULT_BATCH_ROWS,
                CompressionUtil.CodecType.NO_COMPRESSION);
        backend.persistBatch(Map.of("nose_state", List.of(new NoseState())));
        backend.dumpToParquet();
        assertDoesNotThrow(backend::dumpToParquet);
        assertDoesNotThrow(backend::close);
    }

    @Test
    void flushIsANoOp(@TempDir Path tmp) throws Exception {
        ArrowIpcBackend backend = new ArrowIpcBackend(tmp, null, DEFAULT_LIMIT_MB, ArrowIpcBackend.DEFAULT_BATCH_ROWS,
                CompressionUtil.CodecType.NO_COMPRESSION);
        try {
            backend.persistBatch(Map.of("nose_state", List.of(new NoseState())));
            assertDoesNotThrow(backend::flush);
            // flush() must not finalize - a further write must still succeed normally.
            assertDoesNotThrow(() -> backend.persistBatch(Map.of("nose_state", List.of(new NoseState()))));
        } finally {
            backend.close();
        }
    }

    @Test
    void allocatorLimitBreachThrowsRatherThanSilentlyGrowingPastTheCap(@TempDir Path tmp) throws Exception {
        // Constructing all 22 tables' default-capacity vectors costs ~5MB regardless of
        // batchRows (Arrow's own fixed default per-vector initial capacity, not ours) -
        // measured empirically. 8MB comfortably covers that baseline; a large batchRows (so
        // nothing auto-flushes/releases memory mid-write) means enough rows of a long string
        // column must eventually exceed the remaining headroom.
        ArrowIpcBackend backend = new ArrowIpcBackend(tmp, null, 8, 1_000_000, CompressionUtil.CodecType.NO_COMPRESSION);
        try {
            String longValue = "x".repeat(500);
            List<PersistenceState> states = new ArrayList<>();
            for (int i = 0; i < 50_000; i++) {
                ChangeStimulusState css = new ChangeStimulusState();
                css.setComponentClass(longValue);
                states.add(css);
            }
            assertThrows(OutOfMemoryException.class,
                    () -> backend.persistBatch(Map.of("change_stimulus_state", states)));
        } finally {
            backend.close();
        }
    }
}
