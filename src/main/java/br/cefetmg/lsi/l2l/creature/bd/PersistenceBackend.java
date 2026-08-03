package br.cefetmg.lsi.l2l.creature.bd;

import java.util.List;
import java.util.Map;

/**
 * Strategy interface for how {@link BDActor} actually persists a batch of
 * {@link PersistenceState}s, grouped by table name (produced by {@code BDActor.expand()}/
 * {@code tableFor()}, which are backend-agnostic and stay in {@code BDActor}).
 *
 * <p>{@link ParquetBackend} - writes Parquet files directly, no DB/SQL layer at all - is the
 * sole implementation, constructed by {@link PersistenceExtension}. An earlier embedded-DuckDB
 * backend was tried and removed once Parquet proved the better path; kept behind this
 * interface so a future alternative doesn't need to touch {@link BDActor} or callers. See
 * docs/plans/parquet-write-path.md.
 */
public interface PersistenceBackend {

    /** Persist every state in this batch. Table names match {@link PersistenceExtension.Impl#TABLES}. */
    void persistBatch(Map<String, List<PersistenceState>> byTable) throws Exception;

    /**
     * Durably commit/make-visible everything written so far - called only on an explicit
     * {@link Flush} request, not after every batch (see {@link ParquetBackend#flush()}'s
     * javadoc for why it's a deliberate no-op there).
     */
    void flush() throws Exception;

    /**
     * Finalize the raw per-table Parquet output under {@code saveDir/raw}. Since
     * {@link ParquetBackend} already writes Parquet natively, this and {@link #flush()} both
     * mean "finalize now" and are idempotent together there.
     */
    void dumpToParquet() throws Exception;

    /** Release all held resources (connections, open writers). */
    void close() throws Exception;
}
