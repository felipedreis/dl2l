package br.cefetmg.lsi.l2l.creature.bd;

import java.util.List;
import java.util.Map;

/**
 * Strategy interface for how {@link BDActor} actually persists a batch of
 * {@link PersistenceState}s, grouped by table name (produced by {@code BDActor.expand()}/
 * {@code tableFor()}, which are backend-agnostic and stay in {@code BDActor}).
 *
 * <p>{@link ArrowIpcBackend} - writes off-heap Arrow IPC stream files, no DB/SQL layer at all -
 * is the sole implementation, constructed by {@link PersistenceExtension}. Two earlier backends
 * (embedded-DuckDB, then direct-Parquet {@code ParquetBackend}) were tried and removed as each
 * proved the wrong tool - see docs/plans/arrow-ipc-write-path.md (and, for the direct-Parquet
 * history, docs/plans/parquet-write-path.md). Kept behind this interface so a future alternative
 * doesn't need to touch {@link BDActor} or callers.
 */
public interface PersistenceBackend {

    /** Persist every state in this batch. Table names match {@link TableSchemas#ALL}. */
    void persistBatch(Map<String, List<PersistenceState>> byTable) throws Exception;

    /**
     * Durably commit/make-visible everything written so far - called only on an explicit
     * {@link Flush} request, not after every batch (see {@link ArrowIpcBackend#flush()}'s
     * javadoc for why it's a deliberate no-op there).
     */
    void flush() throws Exception;

    /**
     * Finalize the raw per-table output under {@code saveDir/raw} (name kept from the
     * direct-Parquet era - see {@link ArrowIpcBackend}'s javadoc for why renaming isn't worth
     * it). This and {@link #flush()} both mean "finalize now" and are idempotent together.
     */
    void dumpToParquet() throws Exception;

    /** Release all held resources (connections, open writers). */
    void close() throws Exception;
}
