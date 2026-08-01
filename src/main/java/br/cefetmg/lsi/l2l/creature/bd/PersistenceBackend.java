package br.cefetmg.lsi.l2l.creature.bd;

import java.util.List;
import java.util.Map;

/**
 * Strategy interface for how {@link BDActor} actually persists a batch of
 * {@link PersistenceState}s, grouped by table name (produced by {@code BDActor.expand()}/
 * {@code tableFor()}, which are backend-agnostic and stay in {@code BDActor}).
 *
 * <p>Two implementations, selected in {@link PersistenceExtension} via the
 * {@code PERSISTENCE_BACKEND} env var ({@code duckdb} default, or {@code parquet}):
 * <ul>
 *   <li>{@link DuckDBBackend} - embedded DuckDB, SQL-based.</li>
 *   <li>{@link ParquetBackend} - writes Parquet files directly, no DB/SQL layer at all.</li>
 * </ul>
 * See docs/plans/parquet-write-path.md.
 */
public interface PersistenceBackend {

    /** Persist every state in this batch. Table names match {@link PersistenceExtension.Impl#TABLES}. */
    void persistBatch(Map<String, List<PersistenceState>> byTable) throws Exception;

    /**
     * Durably commit/make-visible everything written so far - called only on an explicit
     * {@link Flush} request (not after every batch - see {@link DuckDBBackend}'s javadoc for
     * why that matters for cost).
     */
    void flush() throws Exception;

    /**
     * Finalize the raw per-table Parquet output under {@code saveDir/raw}. For backends that
     * don't already write Parquet natively (e.g. {@link DuckDBBackend}), this does the actual
     * export; for backends where every write already lands directly in the final file (e.g.
     * {@link ParquetBackend}), this and {@link #flush()} both mean "finalize now" and are
     * idempotent together.
     */
    void dumpToParquet() throws Exception;

    /** Release all held resources (connections, open writers). */
    void close() throws Exception;
}
