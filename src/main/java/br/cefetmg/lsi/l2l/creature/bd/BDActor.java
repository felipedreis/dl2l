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
 * <p>The actual write mechanics live behind {@link PersistenceBackend} ({@link ParquetBackend},
 * constructed by {@link PersistenceExtension}) - this class only owns the actor protocol
 * (batching, {@link Flush}/{@link DumpParquet} handling) and the backend-agnostic object-graph
 * expansion/table-grouping ({@link #expand}/{@link #tableFor}). See
 * docs/plans/parquet-write-path.md.
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
                backend.flush();
                sender().tell(new FlushAck(), self());
                return;
            }
            if (batch.size() == 1 && batch.get(0) instanceof DumpParquet) {
                backend.dumpToParquet();
                sender().tell(new DumpParquetAck(), self());
                return;
            }

            logger.fine("Persisting batch of " + batch.size() + " message(s)");
            metricsExt.setGauge("dl2l_bdactor_batch_size", batch.size());

            // Remaining backlog still queued after this dequeue() - distinct from batch size
            // above (what THIS transaction is about to commit). Sustained, non-decreasing
            // growth here is the signal docs/plans/bdactor-async-persistence-with-drain.md §5
            // named as the trigger to revisit backpressure - see
            // docs/plans/issue-77-bdactor-oom-fix.md.
            metricsExt.setGauge("dl2l_bdactor_queue_depth",
                    ((ActorRefWithCell) getSelf()).underlying().numberOfMessages());

            long startNanos = System.nanoTime();
            persistBatch(batch);
            metricsExt.setGauge("dl2l_bdactor_persist_duration_seconds",
                    (System.nanoTime() - startNanos) / 1_000_000_000.0);

        } else if (message instanceof PoisonPill) {
            getContext().stop(self());
            logger.info("BDActor gonna stop");
        } else
            unhandled(message);
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

        if (ps instanceof ChangeStimulusState css) {
            if (css.getReceivedStimulus() != null) {
                for (StimulusState s : css.getReceivedStimulus()) expand(s, working);
            }
            if (css.getEmittedStimulus() != null) {
                for (StimulusState s : css.getEmittedStimulus()) expand(s, working);
            }
        } else if (ps instanceof InternalDynamicState ids) {
            expand(ids.getInitialEmotionalState(), working);
            expand(ids.getFinalEmotionalState(), working);
            expand(ids.getChangeStimulusState(), working);
        }
    }

    private String tableFor(PersistenceState ps) {
        return switch (ps) {
            case ChangeStimulusState ignored -> "change_stimulus_state";
            case StimulusState ignored -> "stimulus_state";
            case CreatureState ignored -> "creature_state";
            case EmotionalState ignored -> "emotional_state";
            case InternalDynamicState ignored -> "internal_dynamic_state";
            case EyeState ignored -> "eye_state";
            case ObjectSeenState ignored -> "object_seen_state";
            case MouthInteractionState ignored -> "mouth_interactions_state";
            case NoseState ignored -> "nose_state";
            case ObjectSmeltState ignored -> "object_smelt_state";
            case ChosenActionState ignored -> "chosen_action_state";
            case BodyState ignored -> "body_state";
            case BehaviouralEfficiencyState ignored -> "behavioural_efficiency_state";
            case RegulationBatchStat ignored -> "regulation_batch_stat";
            case EngramState ignored -> "engram_state";
            case SleepEpisodeState ignored -> "sleep_episode_state";
            case ConsolidationEpisodeStat ignored -> "consolidation_episode_stat";
            case ConsolidationBatchStat ignored -> "consolidation_batch_stat";
            case MemoryTraceStat ignored -> "memory_trace_stat";
            case ExpectancyState ignored -> "expectancy_state";
            case NeuromodulatorStateLog ignored -> "neuromodulator_state_log";
            case EndocrineStateLog ignored -> "endocrine_state_log";
            default -> throw new IllegalArgumentException("Unknown PersistenceState type: " + ps.getClass());
        };
    }

    @Override
    public void postStop() throws Exception {
        super.postStop();
        backend.close();
    }
}
