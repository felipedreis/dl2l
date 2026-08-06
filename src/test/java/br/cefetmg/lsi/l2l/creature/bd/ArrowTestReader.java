package br.cefetmg.lsi.l2l.creature.bd;

import org.apache.arrow.vector.BigIntVector;
import org.apache.arrow.vector.BitVector;
import org.apache.arrow.vector.VarCharVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.ipc.ArrowStreamReader;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads a raw {@code <table>.arrow} file written by {@link ArrowIpcBackend} back into plain
 * Java values, keyed by column name. Test-only.
 *
 * <p>Originally private to {@code ArrowIpcBackendTest}; lifted here (issue #85) so the
 * integration tests can assert on what a real simulation actually persisted rather than on
 * in-process state. Reading the real file is the point - it exercises the same bytes the
 * extraction pipeline consumes, so a test and the downstream analysis cannot disagree about
 * what a column means.
 */
public final class ArrowTestReader {

    private ArrowTestReader() {
    }

    /** Reads every batch of a raw table file back, concatenated, keyed by column name. */
    public static Map<String, List<Object>> readArrowFile(Path file) throws IOException {
        Map<String, List<Object>> columns = new LinkedHashMap<>();
        try (var fis = new FileInputStream(file.toFile());
             var allocator = new org.apache.arrow.memory.RootAllocator(Long.MAX_VALUE);
             ArrowStreamReader reader = new ArrowStreamReader(fis, allocator)) {
            while (reader.loadNextBatch()) {
                VectorSchemaRoot root = reader.getVectorSchemaRoot();
                for (var vector : root.getFieldVectors()) {
                    List<Object> values = columns.computeIfAbsent(vector.getField().getName(), k -> new ArrayList<>());
                    for (int i = 0; i < root.getRowCount(); i++) {
                        values.add(vector.isNull(i) ? null : readValue(vector, i));
                    }
                }
            }
        }
        return columns;
    }

    public static Object readValue(org.apache.arrow.vector.FieldVector vector, int i) {
        if (vector instanceof VarCharVector v) return new String(v.get(i), StandardCharsets.UTF_8);
        if (vector instanceof BigIntVector v) return v.get(i);
        if (vector instanceof org.apache.arrow.vector.IntVector v) return v.get(i);
        if (vector instanceof org.apache.arrow.vector.Float8Vector v) return v.get(i);
        if (vector instanceof BitVector v) return v.get(i) != 0;
        throw new AssertionError("unhandled vector type: " + vector.getClass());
    }

    /** Row count of a column map, or 0 when the table is empty. */
    public static int rowCount(Map<String, List<Object>> columns) {
        return columns.isEmpty() ? 0 : columns.values().iterator().next().size();
    }
}
