package br.cefetmg.lsi.l2l.creature.bd;

import br.cefetmg.lsi.l2l.metrics.MetricsExtension;
import org.apache.arrow.compression.CommonsCompressionFactory;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.RootAllocator;
import org.apache.arrow.vector.BigIntVector;
import org.apache.arrow.vector.BitVector;
import org.apache.arrow.vector.FieldVector;
import org.apache.arrow.vector.Float8Vector;
import org.apache.arrow.vector.IntVector;
import org.apache.arrow.vector.VarCharVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.compression.CompressionUtil;
import org.apache.arrow.vector.ipc.ArrowStreamWriter;
import org.apache.arrow.vector.ipc.message.IpcOption;
import org.apache.arrow.vector.types.FloatingPointPrecision;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.Schema;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * {@link PersistenceBackend} implementation - writes one Arrow IPC <b>stream</b> file per
 * table (not the File format - see below) into off-heap buffers, replacing the direct-Parquet
 * {@code ParquetBackend}/{@code TunedParquetWriter} pair (see docs/plans/arrow-ipc-write-path.md;
 * that earlier design is still in git history). Parquet stays downstream, as the extraction
 * output ({@code scripts/dl2l_data/extract.py} now reads these {@code .arrow} files directly and
 * still writes Parquet for analysis/HF - unchanged for every consumer past extraction).
 *
 * <p><b>Why Arrow over direct Parquet:</b> Parquet encoding (dictionary + Snappy, on-heap
 * row-group buffers) is CPU-expensive exactly where CPU is scarce (CCAD's cgroup-limited
 * slices), and every buffer it holds counts against {@code -Xmx}. Arrow's columnar buffers are
 * near-zero-encode-cost appends and, via {@code arrow-memory-unsafe}, entirely off-heap -
 * invisible to G1, outside the heap limit that OOM'd CCAD trials.
 *
 * <p><b>Stream format, not File format</b> - deliberate: {@link ArrowStreamWriter} never seeks
 * back to patch a footer, so a JVM killed mid-run (the watchdog's {@code Runtime.halt}, a SIGKILL,
 * an OOM) leaves every already-{@link #flushBatch}'d record batch readable via
 * {@code pyarrow.ipc.open_stream} - the raw dump keeps the "backup" role {@code ParquetBackend}'s
 * javadoc described, just truncated at the last complete batch instead of a complete file.
 *
 * <p><b>No upsert semantics</b> - same as {@code ParquetBackend} before it: {@code creature_state}
 * birth+death and the cross-{@code persist()}-call {@code change_stimulus_state}/
 * {@code stimulus_state} duplicate references land as duplicate rows, deduped downstream (see
 * PR 2's extraction-side dedup), not here.
 *
 * <p>{@link #flush()} stays a deliberate no-op - see {@code ParquetBackend#flush()}'s original
 * javadoc for the full "used-after-close on the natural-death path" incident this avoids; nothing
 * changed about {@link Flush}'s actual contract (mailbox FIFO ordering already guarantees every
 * write queued before a {@link Flush} is committed by the time it's processed). Only
 * {@link #dumpToParquet()} (name kept - it now means "finalize the raw Arrow dump", not literally
 * Parquet; renaming would touch {@code Holder}/{@code Messages} for zero behavior change) finalizes,
 * exactly once, idempotently via {@link #closed}.
 */
public class ArrowIpcBackend implements PersistenceBackend {

    private static final Logger logger = Logger.getLogger(ArrowIpcBackend.class.getName());

    static final int DEFAULT_BATCH_ROWS = 4096;
    static final long DEFAULT_ALLOCATOR_LIMIT_MB = 256;

    private final BufferAllocator allocator;
    private final Map<String, TableWriter> writers = new LinkedHashMap<>();
    private final MetricsExtension.Impl metricsExt;
    private final AtomicLong lateWrites = new AtomicLong();
    private volatile boolean closed = false;

    public ArrowIpcBackend(Path rawDumpDir, MetricsExtension.Impl metricsExt) throws Exception {
        this(rawDumpDir, metricsExt, allocatorLimitMbFromEnv(), batchRowsFromEnv(), compressionFromEnv());
    }

    /** Package-visible full constructor - lets tests exercise small limits/batch sizes directly. */
    ArrowIpcBackend(Path rawDumpDir, MetricsExtension.Impl metricsExt, long allocatorLimitMb, int batchRows,
                     CompressionUtil.CodecType compression) throws Exception {
        this.metricsExt = metricsExt;
        this.allocator = new RootAllocator(allocatorLimitMb * 1024L * 1024L);
        try {
            for (TableSchemas.TableDef<?> def : TableSchemas.ALL) {
                Path out = rawDumpDir.resolve(def.table() + ".arrow");
                writers.put(def.table(), new TableWriter(def, allocator, out, batchRows, compression));
            }
            selfCheck();
        } catch (Exception e) {
            close();
            throw e;
        }
    }

    private static long allocatorLimitMbFromEnv() {
        String v = System.getenv("DL2L_ARROW_ALLOCATOR_LIMIT_MB");
        return v != null ? Long.parseLong(v) : DEFAULT_ALLOCATOR_LIMIT_MB;
    }

    private static int batchRowsFromEnv() {
        String v = System.getenv("DL2L_ARROW_BATCH_ROWS");
        return v != null ? Integer.parseInt(v) : DEFAULT_BATCH_ROWS;
    }

    private static CompressionUtil.CodecType compressionFromEnv() {
        String v = System.getenv("DL2L_ARROW_COMPRESSION");
        if (v == null || v.isBlank() || v.equalsIgnoreCase("none")) return CompressionUtil.CodecType.NO_COMPRESSION;
        return switch (v.toLowerCase()) {
            case "lz4" -> CompressionUtil.CodecType.LZ4_FRAME;
            case "zstd" -> CompressionUtil.CodecType.ZSTD;
            default -> throw new IllegalArgumentException(
                    "DL2L_ARROW_COMPRESSION must be one of none|lz4|zstd, got: " + v);
        };
    }

    /**
     * 1-row in-memory write+read round trip through the real allocator, run once at construction
     * so a missing {@code --add-opens} flag (mandatory for {@code arrow-memory-unsafe} on JDK 16+)
     * fails the holder loudly at {@code preStart()} instead of surfacing as an obscure
     * {@code ExceptionInInitializerError} on the first real write, deep in a creature's hot path.
     */
    private void selfCheck() {
        try (VarCharVector v = new VarCharVector("dl2l-arrow-selfcheck", allocator)) {
            v.allocateNew();
            v.setSafe(0, "ok".getBytes(StandardCharsets.UTF_8));
            v.setValueCount(1);
            String read = new String(v.get(0), StandardCharsets.UTF_8);
            if (!"ok".equals(read)) {
                throw new IllegalStateException("Arrow allocator self-check round trip mismatch: read '" + read + "'");
            }
        } catch (Exception e) {
            throw new IllegalStateException(
                    "ArrowIpcBackend self-check failed - the JVM is very likely missing the required "
                            + "--add-opens=java.base/java.nio=ALL-UNNAMED flag "
                            + "(see scripts/run-dl2l.sh). Original error: " + e, e);
        }
    }

    @Override
    public void persistBatch(Map<String, List<PersistenceState>> byTable) throws IOException {
        for (Map.Entry<String, List<PersistenceState>> e : byTable.entrySet()) {
            TableWriter writer = writers.get(e.getKey());
            if (writer == null) {
                throw new IllegalArgumentException("No Arrow writer registered for table: " + e.getKey());
            }
            for (PersistenceState ps : e.getValue()) {
                if (writer.finalized()) {
                    long total = lateWrites.incrementAndGet();
                    logger.severe("DL2L-PERSISTENCE-LATE-WRITE table=" + e.getKey()
                            + " totalLateWrites=" + total + " - dropped, backend already finalized");
                    if (metricsExt != null) metricsExt.setGauge("dl2l_persistence_late_writes_total", total);
                    continue;
                }
                writer.write(ps);
            }
        }
        if (metricsExt != null) {
            metricsExt.setGauge("dl2l_arrow_allocated_bytes", allocator.getAllocatedMemory());
        }
    }

    /** Deliberate no-op - see class javadoc. */
    @Override
    public void flush() {
    }

    @Override
    public void dumpToParquet() throws IOException {
        finalizeAll();
    }

    @Override
    public void close() throws IOException {
        finalizeAll();
    }

    private synchronized void finalizeAll() throws IOException {
        if (closed) return;
        IOException firstFailure = null;
        for (TableWriter writer : writers.values()) {
            try {
                writer.finalizeWriter();
            } catch (IOException e) {
                logger.log(Level.WARNING, "Failed to finalize an Arrow table writer - continuing with the rest", e);
                if (firstFailure == null) firstFailure = e;
            }
        }
        try {
            allocator.close();
        } catch (RuntimeException e) {
            // A prior write-time failure (e.g. an allocator-limit OutOfMemoryException mid-batch,
            // see ArrowIpcBackendTest) can leave a vector holding more live memory than its
            // declared row count reflects, which BaseAllocator.close() reports as a "leak" even
            // though every buffer is still reachable from a VectorSchemaRoot we're discarding
            // right here. BDActor.postStop() calls close() unconditionally after ANY crash
            // (including this one) - letting this secondary accounting complaint propagate would
            // mask the original, more actionable DL2L-FATAL-PERSISTENCE-WRITE-ERROR log line.
            logger.log(Level.WARNING, "Arrow allocator reported leaked memory while closing "
                    + "(expected after an earlier write failure) - logging, not propagating", e);
        } finally {
            closed = true;
        }
        if (firstFailure != null) throw firstFailure;
    }

    long allocatedBytes() {
        return allocator.getAllocatedMemory();
    }

    // --- per-table writer -----------------------------------------------------------------

    /**
     * One {@link VectorSchemaRoot}/{@link ArrowStreamWriter} pair per table, opened once at
     * {@link ArrowIpcBackend} construction and held for the actor's whole lifetime - mirrors
     * {@code ParquetBackend}'s one-writer-per-table lifecycle exactly.
     */
    private static final class TableWriter {

        private final List<TableSchemas.Column<PersistenceState>> columns;
        private final int batchRows;
        private final VectorSchemaRoot root;
        private final FileOutputStream fos;
        private final ArrowStreamWriter writer;
        private int rowsInBatch = 0;
        private volatile boolean finalized = false;

        @SuppressWarnings("unchecked")
        TableWriter(TableSchemas.TableDef<?> def, BufferAllocator allocator, Path out, int batchRows,
                    CompressionUtil.CodecType compression) throws IOException {
            this.columns = (List<TableSchemas.Column<PersistenceState>>) (List<?>) def.columns();
            this.batchRows = batchRows;

            List<Field> fields = new ArrayList<>(columns.size());
            for (TableSchemas.Column<PersistenceState> c : columns) {
                fields.add(Field.nullable(c.name(), arrowType(c.type())));
            }
            Schema schema = new Schema(fields);
            this.root = VectorSchemaRoot.create(schema, allocator);
            root.allocateNew();

            this.fos = new FileOutputStream(out.toFile());
            this.writer = (compression == CompressionUtil.CodecType.NO_COMPRESSION)
                    ? new ArrowStreamWriter(root, null, fos.getChannel())
                    : new ArrowStreamWriter(root, null, fos.getChannel(), IpcOption.DEFAULT,
                            CommonsCompressionFactory.INSTANCE, compression);
            writer.start();
        }

        private static ArrowType arrowType(TableSchemas.ColType type) {
            return switch (type) {
                case STRING -> ArrowType.Utf8.INSTANCE;
                case INT32 -> new ArrowType.Int(32, true);
                case INT64 -> new ArrowType.Int(64, true);
                case DOUBLE -> new ArrowType.FloatingPoint(FloatingPointPrecision.DOUBLE);
                case BOOLEAN -> ArrowType.Bool.INSTANCE;
            };
        }

        void write(PersistenceState ps) throws IOException {
            int row = rowsInBatch;
            for (int i = 0; i < columns.size(); i++) {
                TableSchemas.Column<PersistenceState> col = columns.get(i);
                Object value = col.getter().apply(ps);
                // Unset slot after allocateNew()/reset() already reads back as null - only
                // ever call setSafe for a present value. This structurally kills the
                // parquet-floor writeIfPresent NPE bug class ParquetBackend had to work
                // around (see its javadoc): there is no "write an explicit null" API to
                // misuse here.
                if (value != null) {
                    setValue(root.getVector(i), col.type(), row, value);
                }
            }
            rowsInBatch++;
            if (rowsInBatch >= batchRows) {
                flushBatch();
            }
        }

        private static void setValue(FieldVector vector, TableSchemas.ColType type, int row, Object value) {
            switch (type) {
                case STRING -> ((VarCharVector) vector).setSafe(row, ((String) value).getBytes(StandardCharsets.UTF_8));
                case INT32 -> ((IntVector) vector).setSafe(row, (Integer) value);
                case INT64 -> ((BigIntVector) vector).setSafe(row, (Long) value);
                case DOUBLE -> ((Float8Vector) vector).setSafe(row, (Double) value);
                case BOOLEAN -> ((BitVector) vector).setSafe(row, ((Boolean) value) ? 1 : 0);
            }
        }

        void flushBatch() throws IOException {
            if (rowsInBatch == 0) return;
            root.setRowCount(rowsInBatch);
            writer.writeBatch();
            rowsInBatch = 0;
            root.allocateNew();
        }

        boolean finalized() {
            return finalized;
        }

        void finalizeWriter() throws IOException {
            if (finalized) return;
            try {
                flushBatch();
                writer.end();
            } finally {
                writer.close();
                fos.close();
                root.close();
                finalized = true;
            }
        }
    }
}
