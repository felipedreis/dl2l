package br.cefetmg.lsi.l2l.creature.bd;

import akka.actor.ActorRefWithCell;
import akka.actor.PoisonPill;
import akka.actor.UntypedActor;
import br.cefetmg.lsi.l2l.metrics.MetricsExtension;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/**
 * One per JVM (owned by {@link PersistenceExtension}), on its own {@code bd-dispatcher}
 * (a {@code PinnedDispatcher} - single dedicated OS thread). Every creature component's
 * {@code persist()} call routes here via {@code creature.bd().tell(state)} instead of
 * blocking {@code component-dispatcher} with a synchronous transaction - see
 * docs/plans/bdactor-async-persistence-with-drain.md.
 *
 * <p>Issue #79: this is now the SOLE writer for the whole JVM - JPA/EclipseLink is gone (see
 * docs/plans/remove-jpa-persistence-layer.md). {@link CreatureState} writes
 * ({@code CreatureActor}) and the memory-consolidation stat writes ({@code MemoryConsolidator},
 * {@code MemoryTraceConsolidator}) now route here too instead of each opening their own
 * connection, avoiding any repeat of the "~70-100 separate connections" incident documented
 * on {@link PersistenceExtension}.
 *
 * <p>The actual write mechanics live behind {@link PersistenceBackend} ({@link ArrowIpcBackend},
 * constructed by {@link PersistenceExtension}) - this class only owns the actor protocol
 * (batching, {@link Flush}/{@link DumpParquet} handling) and the backend-agnostic object-graph
 * expansion/table-grouping ({@link #expand}/{@link #tableFor}, the latter delegating to
 * {@link TableSchemas}). See docs/plans/arrow-ipc-write-path.md.
 *
 * <p>Every entity's surrogate id is a client-assigned {@link java.util.UUID} (see each
 * entity class), so insert order never matters and no generated-id round trip is needed.
 * Relies on {@link br.cefetmg.lsi.l2l.common.ComponentMessageQueue} batching every
 * {@link PersistenceState} queued between polls into one {@code List}, delivered as a single
 * {@code onReceive} call, so one backend call commits an entire backlog at once.
 */
public class BDActor extends UntypedActor {

    private final PersistenceBackend backend;

    private final MetricsExtension.Impl metricsExt;

    private final Logger logger = Logger.getLogger(BDActor.class.getSimpleName());

    public BDActor() throws Exception {
        this.backend = PersistenceExtension.of(getContext().system()).newBackend();
        this.metricsExt = MetricsExtension.of(getContext().system());
    }

    @Override
    public void onReceive(Object message) throws Exception {
        if (message instanceof List) {
            List<?> batch = (List<?>) message;
            if (batch.size() == 1 && batch.get(0) instanceof Flush) {
                // Mailbox FIFO ordering guarantees every PersistenceState queued strictly
                // before this Flush was already delivered and committed in an earlier
                // onReceive call (ComponentMessageQueue never merges an unrecognized message
                // type, e.g. Flush, into an in-progress Stimulus/PersistenceState batch) - so
                // backend.flush() only needs to make already-written data durable/visible, not
                // process anything new.
                try {
                    backend.flush();
                } catch (Exception e) {
                    logFatalWriteError("flush()", e);
                    throw e;
                }
                sender().tell(new FlushAck(), self());
                return;
            }
            if (batch.size() == 1 && batch.get(0) instanceof DumpParquet) {
                try {
                    backend.dumpToParquet();
                } catch (Exception e) {
                    logFatalWriteError("dumpToParquet()", e);
                    throw e;
                }
                sender().tell(new DumpParquetAck(), self());
                return;
            }

            logger.fine("Persisting batch of " + batch.size() + " message(s)");
            metricsExt.setGauge("dl2l_bdactor_batch_size", batch.size());

            // Remaining backlog still queued after this dequeue() - distinct from batch size
            // above (what THIS transaction is about to commit). Sustained, non-decreasing
            // growth here is the signal docs/plans/bdactor-async-persistence-with-drain.md §5
            // named as the trigger to revisit backpressure - see
            // docs/plans/issue-77-bdactor-oom-fix.md. Also the depth the queue-depth watchdog
            // polls independently (see docs/plans/arrow-ipc-write-path.md, W3).
            metricsExt.setGauge("dl2l_bdactor_queue_depth",
                    ((ActorRefWithCell) getSelf()).underlying().numberOfMessages());

            long startNanos = System.nanoTime();
            try {
                persistBatch(batch);
            } catch (Exception e) {
                logFatalWriteError("persistBatch(" + batch.size() + " message(s))", e);
                throw e;
            }
            metricsExt.setGauge("dl2l_bdactor_persist_duration_seconds",
                    (System.nanoTime() - startNanos) / 1_000_000_000.0);

        } else if (message instanceof PoisonPill) {
            getContext().stop(self());
            logger.info("BDActor gonna stop");
        } else
            unhandled(message);
    }

    /**
     * Logs {@code DL2L-FATAL-PERSISTENCE-WRITE-ERROR} right next to the stack trace before the
     * caller rethrows - this project's {@code StoppingSupervisorStrategy} stops (never
     * restarts) an actor on an uncaught exception, so BDActor dies here regardless; the marker
     * exists so that death is unambiguous in the log rather than inferred from
     * {@link PersistenceExtension.BdActorDeathWatch}'s later, less specific
     * {@code DL2L-FATAL-PERSISTENCE-DEAD}. See docs/plans/arrow-ipc-write-path.md, W3.
     */
    private void logFatalWriteError(String op, Exception e) {
        logger.severe("DL2L-FATAL-PERSISTENCE-WRITE-ERROR op=" + op + " error=" + e);
    }

    /**
     * Expand each top-level message into the full set of rows to write, dedup by object
     * identity, group by table, and hand off to the backend as one unit. Mirrors the
     * "one dequeue() = one transaction" invariant the old JPA design had.
     */
    private void persistBatch(List<?> batch) throws Exception {
        Set<PersistenceState> working = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Object o : batch) {
            if (o instanceof PersistenceState ps) {
                expand(ps, working);
            } else if (o instanceof PersistenceState[] arr) {
                for (PersistenceState ps : arr) expand(ps, working);
            }
        }
        if (working.isEmpty()) return;

        Map<String, List<PersistenceState>> byTable = new LinkedHashMap<>();
        for (PersistenceState ps : working) {
            byTable.computeIfAbsent(tableFor(ps), t -> new ArrayList<>()).add(ps);
        }

        backend.persistBatch(byTable);
    }

    /**
     * Recursively pulls in the handful of entities that are ONLY reachable by walking a
     * parent's object graph - never separately passed as their own top-level array element
     * at any call site (confirmed by exhaustive call-site review, see
     * docs/plans/remove-jpa-persistence-layer.md). Every other relationship (EyeState/
     * BodyState/etc. referencing a ChangeStimulusState) is always ALSO independently
     * top-level-persisted somewhere - sometimes in a separate persist() call entirely (e.g.
     * Eye.java calls persist(change, seen) then persist(change, eyeState) with the same
     * change instance - see CreatureComponent.persist()'s javadoc).
     */
    private void expand(PersistenceState ps, Set<PersistenceState> working) {
        if (ps == null || !working.add(ps)) return; // null-safe; add() false => already queued

        // StimulusState is DELIBERATELY not expanded: nothing reads it, and it is by a wide
        // margin the most expensive thing we write. Measured on a p84 current_nomem trial,
        // stimulus_state.arrow was 3.2 GB of a 5.6 GB dump — 60% of the raw output — while
        // scripts/dl2l_data/tables.py references it in exactly zero of its 20 queries. It is
        // what exhausted the CCAD disk quota and lost four of six arms of the campaign.
        //
        // The one remaining reference is scripts/pg_extract.py, the legacy CSV extractor,
        // which reads a PostgreSQL database that no longer exists anywhere (issue #79 removed
        // the write path entirely) — so it cannot be run against new data by construction.
        //
        // The table stays registered in TableSchemas so the ported-schema block there remains
        // an accurate record of the original 22; it simply never receives rows, and
        // ArrowIpcBackend creates a file only for tables that do. Restoring the behaviour is
        // re-adding the two loops below.
        if (ps instanceof InternalDynamicState ids) {
            expand(ids.getInitialEmotionalState(), working);
            expand(ids.getFinalEmotionalState(), working);
            expand(ids.getChangeStimulusState(), working);
        }
    }

    /** Delegates to the single declarative schema - see {@link TableSchemas}. */
    private String tableFor(PersistenceState ps) {
        return TableSchemas.forState(ps).table();
    }

    @Override
    public void postStop() throws Exception {
        super.postStop();
        backend.close();
    }
}
