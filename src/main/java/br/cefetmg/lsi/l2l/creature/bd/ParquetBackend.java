package br.cefetmg.lsi.l2l.creature.bd;

import blue.strategic.parquet.Dehydrator;
import blue.strategic.parquet.ValueWriter;
import br.cefetmg.lsi.l2l.common.SequentialId;
import org.apache.parquet.schema.MessageType;
import org.apache.parquet.schema.Type;
import org.apache.parquet.schema.Types;

import java.io.File;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.apache.parquet.schema.LogicalTypeAnnotation.stringType;
import static org.apache.parquet.schema.PrimitiveType.PrimitiveTypeName.BINARY;
import static org.apache.parquet.schema.PrimitiveType.PrimitiveTypeName.BOOLEAN;
import static org.apache.parquet.schema.PrimitiveType.PrimitiveTypeName.DOUBLE;
import static org.apache.parquet.schema.PrimitiveType.PrimitiveTypeName.INT32;
import static org.apache.parquet.schema.PrimitiveType.PrimitiveTypeName.INT64;

/**
 * {@link PersistenceBackend} strategy alternative to {@link DuckDBBackend} - writes Parquet
 * files directly via {@code parquet-floor} (a Hadoop-free wrapper around the standard
 * {@code parquet-column}/{@code parquet-hadoop} libraries), no SQL/DB layer at all. Exists
 * because the DuckDB write path's real slowness was never conclusively root-caused (see
 * {@link DuckDBBackend#appenders}' javadoc) - this sidesteps the question entirely by
 * removing the intermediary. One {@link TunedParquetWriter} per table, opened once at
 * construction and held for the actor's whole lifetime; {@code write()} buffers rows
 * in-memory and flushes row-groups internally once {@link TunedParquetWriter#ROW_GROUP_SIZE_BYTES}
 * is reached (analogous to DuckDB's Appender auto-flush threshold) - see that class's javadoc
 * for why the row-group size needed overriding away from parquet-hadoop's 128MiB default.
 *
 * <p><b>No upsert semantics</b> - unlike {@link DuckDBBackend}, there is no equivalent of
 * {@code ON CONFLICT DO UPDATE}. The three tables that legitimately get re-persisted with
 * the same id (see {@code DuckDBBackend.UPSERT_TABLES}' javadoc: {@code creature_state}
 * birth+death, {@code change_stimulus_state}/{@code stimulus_state}'s cross-persist()-call
 * duplicate references) land here as duplicate rows instead of being collapsed to one.
 * Deferred by design, per explicit scope: this class validates the *write* path only; the
 * extraction/analysis side (which would need to dedupe by id, e.g. keep-last) is separate,
 * later work - see docs/plans/parquet-write-path.md.
 *
 * <p>{@link #flush()} is a deliberate no-op - see its own javadoc for why (CONFIRMED LIVE:
 * closing writers there crashed a later write). Only {@link #dumpToParquet()} (and
 * {@link #close()}) actually finalize (close) every writer, and only once - idempotent via
 * {@link #closed}.
 */
public class ParquetBackend implements PersistenceBackend {

    private final Map<String, TunedParquetWriter<PersistenceState>> writers = new LinkedHashMap<>();
    private volatile boolean closed = false;

    public ParquetBackend(Path rawDumpDir) throws Exception {
        for (String table : PersistenceExtension.Impl.TABLES) {
            File out = rawDumpDir.resolve(table + ".parquet").toFile();
            writers.put(table, TunedParquetWriter.writeFile(SCHEMAS.get(table), out, DEHYDRATOR));
        }
    }

    @Override
    public void persistBatch(Map<String, List<PersistenceState>> byTable) throws Exception {
        for (Map.Entry<String, List<PersistenceState>> e : byTable.entrySet()) {
            TunedParquetWriter<PersistenceState> writer = writers.get(e.getKey());
            for (PersistenceState ps : e.getValue()) {
                writer.write(ps);
            }
        }
    }

    /**
     * Deliberate no-op. CONFIRMED LIVE: this used to call {@code finalizeAll()} (matching
     * DuckDBBackend's "Flush means durable/visible now" semantics), which broke on the
     * natural-death path - {@code Holder.handleRemoveObject()} sends its OWN {@code Flush}
     * the moment the last creature dies, strictly BEFORE {@code handleFinish()}'s later
     * {@code Flush}+{@code DumpParquet} - so closing every writer there finalized files that
     * a subsequent write (e.g. a trailing consolidation stat) then crashed trying to write
     * into, exactly the "used-after-close" shape of bug. Unlike DuckDBBackend, nothing here
     * needs an explicit flush to satisfy {@link Flush}'s actual contract ("every write
     * enqueued strictly before this Flush was already processed by the time it's acked") -
     * {@link ParquetWriter#write} is a synchronous in-memory buffer append, already true the
     * instant {@code persistBatch()} returns, via the same mailbox FIFO ordering {@link Flush}
     * already relies on. Only {@link #dumpToParquet()} (called exactly once, genuinely last)
     * needs to finalize files into a state readable by another process.
     */
    @Override
    public void flush() {
    }

    @Override
    public void dumpToParquet() throws Exception {
        finalizeAll();
    }

    @Override
    public void close() throws Exception {
        finalizeAll();
    }

    private synchronized void finalizeAll() throws Exception {
        if (closed) return;
        for (TunedParquetWriter<PersistenceState> writer : writers.values()) {
            writer.close();
        }
        closed = true;
    }

    // --- schema -----------------------------------------------------------------------

    private enum Col {
        ID, UUID_REF, STRING, INT32, INT64, DOUBLE, BOOLEAN
    }

    private record Field(String name, Col type) {
    }

    private static Field f(String name, Col type) {
        return new Field(name, type);
    }

    /** Column lists mirror DuckDBBackend.buildInsertSql()'s exactly, with types added. */
    private static final Map<String, List<Field>> TABLE_COLUMNS = buildTableColumns();

    private static Map<String, List<Field>> buildTableColumns() {
        Map<String, List<Field>> t = new LinkedHashMap<>();
        t.put("change_stimulus_state", List.of(
                f("componentclass", Col.STRING), f("time", Col.INT64), f("key", Col.INT64), f("sequential", Col.INT64)));
        t.put("stimulus_state", List.of(
                f("stimulusclass", Col.STRING), f("type", Col.STRING), f("componentkey", Col.INT64),
                f("stimulusseq", Col.INT64), f("changestimulusemitted_id", Col.UUID_REF),
                f("changestimulusreceived_id", Col.UUID_REF)));
        t.put("creature_state", List.of(
                f("borntime", Col.INT64), f("deadtime", Col.INT64), f("gender", Col.BOOLEAN),
                f("father_key", Col.INT64), f("father_sequential", Col.INT64), f("mother_key", Col.INT64),
                f("mother_sequential", Col.INT64), f("creature_key", Col.INT64), f("creature_sequential", Col.INT64)));
        t.put("emotional_state", List.of(
                f("apathy_arausal", Col.DOUBLE), f("curiosity_arausal", Col.DOUBLE), f("fear_arausal", Col.DOUBLE),
                f("fertility_arausal", Col.DOUBLE), f("hunger_arausal", Col.DOUBLE), f("pain_arausal", Col.DOUBLE),
                f("sleep_arausal", Col.DOUBLE), f("stress_arausal", Col.DOUBLE), f("tedium_arausal", Col.DOUBLE)));
        t.put("internal_dynamic_state", List.of(
                f("changestimulusstate_id", Col.UUID_REF), f("finalemotionalstate_id", Col.UUID_REF),
                f("initialemotionalstate_id", Col.UUID_REF)));
        t.put("eye_state", List.of(
                f("finalopening", Col.DOUBLE), f("finalstartangle", Col.DOUBLE), f("initialopening", Col.DOUBLE),
                f("initialstartangle", Col.DOUBLE), f("changestimulusstate_id", Col.UUID_REF)));
        t.put("object_seen_state", List.of(
                f("angle", Col.DOUBLE), f("direction", Col.DOUBLE), f("distance", Col.DOUBLE), f("type", Col.STRING),
                f("key", Col.INT64), f("sequential", Col.INT64), f("changestimulusstate_id", Col.UUID_REF)));
        t.put("mouth_interactions_state", List.of(
                f("objecttype", Col.STRING), f("type", Col.STRING), f("key", Col.INT64), f("sequential", Col.INT64),
                f("changestimulusstate_id", Col.UUID_REF)));
        t.put("nose_state", List.of(f("key", Col.INT64), f("sequential", Col.INT64)));
        t.put("object_smelt_state", List.of(
                f("objecttype", Col.STRING), f("smelltype", Col.STRING), f("key", Col.INT64), f("sequential", Col.INT64),
                f("changestimulusstate_id", Col.UUID_REF)));
        t.put("chosen_action_state", List.of(
                f("action", Col.STRING), f("actionselectiontype", Col.STRING), f("inference_duration_ms", Col.INT64),
                f("key", Col.INT64), f("sequential", Col.INT64), f("changestimulusstate_id", Col.UUID_REF)));
        t.put("body_state", List.of(
                f("finalx", Col.DOUBLE), f("finaly", Col.DOUBLE), f("initialx", Col.DOUBLE), f("initialy", Col.DOUBLE),
                f("speed", Col.DOUBLE), f("stimulusstate_id", Col.UUID_REF)));
        t.put("behavioural_efficiency_state", List.of(
                f("behaviouralefficiency", Col.DOUBLE), f("complextask", Col.BOOLEAN), f("numberofobjects", Col.INT32),
                f("changestimulusstate_id", Col.UUID_REF)));
        t.put("regulation_batch_stat", List.of(
                f("batchsize", Col.INT32), f("drivestouchedmask", Col.INT32), f("regulatingcount", Col.INT32),
                f("samedrivecollision", Col.BOOLEAN), f("changestimulusstate_id", Col.UUID_REF)));
        t.put("engram_state", List.of(
                f("action_type", Col.STRING), f("creature_key", Col.INT64), f("cycle_gap", Col.INT64),
                f("eligibility", Col.DOUBLE), f("emotion_delta", Col.DOUBLE), f("lay_cycle", Col.INT64),
                f("reinforced_cycle", Col.INT64)));
        t.put("sleep_episode_state", List.of(
                f("creature_key", Col.INT64), f("duration_ticks", Col.INT32), f("onset_cycle", Col.INT64),
                f("wake_cycle", Col.INT64)));
        t.put("consolidation_episode_stat", List.of(
                f("aborted", Col.BOOLEAN), f("batches_completed", Col.INT32), f("creature_key", Col.INT64),
                f("engram_count", Col.INT32), f("mean_eligibility", Col.DOUBLE), f("onset_cycle", Col.INT64),
                f("std_eligibility", Col.DOUBLE)));
        t.put("consolidation_batch_stat", List.of(
                f("batch_index", Col.INT32), f("batch_size", Col.INT32), f("creature_key", Col.INT64),
                f("loss", Col.DOUBLE), f("onset_cycle", Col.INT64)));
        t.put("memory_trace_stat", List.of(
                f("creature_key", Col.INT64), f("engram_count", Col.INT32), f("groups_consolidated", Col.INT32),
                f("onset_cycle", Col.INT64)));
        t.put("expectancy_state", List.of(
                f("action", Col.STRING), f("creature_key", Col.INT64), f("cycle", Col.INT64), f("drive", Col.STRING),
                f("drive_level", Col.DOUBLE), f("expected", Col.DOUBLE), f("mode", Col.STRING),
                f("reward", Col.DOUBLE), f("rpe", Col.DOUBLE), f("target", Col.STRING)));
        t.put("neuromodulator_state_log", List.of(
                f("circadian_phase", Col.DOUBLE), f("creature_key", Col.INT64), f("dopamine", Col.DOUBLE),
                f("orexin", Col.DOUBLE), f("seq", Col.INT64), f("serotonin", Col.DOUBLE)));
        t.put("endocrine_state_log", List.of(
                f("cortisol_tonic", Col.DOUBLE), f("creature_key", Col.INT64), f("seq", Col.INT64),
                f("stress_level", Col.DOUBLE)));
        return t;
    }

    private static final Map<String, MessageType> SCHEMAS = buildSchemas();

    private static Map<String, MessageType> buildSchemas() {
        Map<String, MessageType> schemas = new LinkedHashMap<>();
        for (Map.Entry<String, List<Field>> e : TABLE_COLUMNS.entrySet()) {
            List<Type> fields = new java.util.ArrayList<>();
            fields.add(Types.required(BINARY).as(stringType()).named("id"));
            for (Field col : e.getValue()) {
                fields.add(switch (col.type()) {
                    case STRING, UUID_REF -> Types.optional(BINARY).as(stringType()).named(col.name());
                    case INT32 -> Types.optional(INT32).named(col.name());
                    case INT64 -> Types.optional(INT64).named(col.name());
                    case DOUBLE -> Types.optional(DOUBLE).named(col.name());
                    case BOOLEAN -> Types.optional(BOOLEAN).named(col.name());
                    case ID -> throw new IllegalStateException("ID is only ever the implicit first column");
                });
            }
            schemas.put(e.getKey(), new MessageType(e.getKey(), fields));
        }
        return schemas;
    }

    // --- dehydration --------------------------------------------------------------------

    /**
     * CONFIRMED LIVE: parquet-floor's {@code ValueWriter.write(name, null)} throws
     * ({@code NullPointerException: Cannot invoke "java.lang.Long.longValue()" because
     * "value" is null}, inside {@code SimpleWriteSupport.writeField}) for at least numeric
     * columns - unlike JDBC's {@code setNull}/DuckDB Appender's {@code appendNull()}, this
     * API represents a null OPTIONAL field by simply never calling {@code write} for that
     * column on that row (Parquet's definition-level tracking marks it absent), not by
     * passing an explicit null value through. This NPE killed BDActor within the first
     * batch of the very first local validation run (StoppingSupervisorStrategy stops rather
     * than restarts the actor - see docs/plans/parquet-write-path.md), and every
     * persist() for the rest of that run silently went to the now-dead actor, producing
     * valid-but-completely-empty output Parquet files with no visible error until the log
     * was inspected directly. Every write below that could be null goes through this
     * instead of a direct {@code w.write}.
     */
    private static void writeIfPresent(ValueWriter w, String name, Object value) {
        if (value != null) w.write(name, value);
    }

    private static String uuidOrNull(UUID id) {
        return id == null ? null : id.toString();
    }

    /**
     * One shared {@link Dehydrator} for every table's {@link ParquetWriter} - each writer
     * only ever receives states of the one type {@code persistBatch()} routes to it (via
     * {@code BDActor.tableFor()}), so a single switch handles all 22 tables without needing
     * per-table dehydrator instances. Column order/names must exactly match {@link #TABLE_COLUMNS}.
     * Every primitive-typed field (long/double/int/boolean, from primitive-returning
     * getters) is written directly - those can never be null. Every String/UUID-derived
     * field goes through {@link #writeIfPresent} - see its javadoc for why.
     */
    private static final Dehydrator<PersistenceState> DEHYDRATOR = (ps, w) -> {
        switch (ps) {
            case ChangeStimulusState css -> {
                w.write("id", css.getId().toString());
                writeIfPresent(w, "componentclass", css.getComponentClass());
                w.write("time", css.getTime());
                writeSequentialId(w, "key", "sequential", css.getComponentID());
            }
            case StimulusState ss -> {
                w.write("id", ss.getId().toString());
                writeIfPresent(w, "stimulusclass", ss.getStimulusClass());
                writeIfPresent(w, "type", ss.getType() != null ? ss.getType().name() : null);
                writeSequentialId(w, "componentkey", "stimulusseq", ss.getStimulusId());
                writeIfPresent(w, "changestimulusemitted_id",
                        uuidOrNull(ss.getChangeStimulusEmitted() != null ? ss.getChangeStimulusEmitted().getId() : null));
                writeIfPresent(w, "changestimulusreceived_id",
                        uuidOrNull(ss.getChangeStimulusReceived() != null ? ss.getChangeStimulusReceived().getId() : null));
            }
            case CreatureState cs -> {
                w.write("id", cs.getId().toString());
                w.write("borntime", cs.getBornTime());
                w.write("deadtime", cs.getDeadTime());
                w.write("gender", cs.isGender());
                writeSequentialId(w, "father_key", "father_sequential", cs.getFatherState());
                writeSequentialId(w, "mother_key", "mother_sequential", cs.getMotherState());
                writeSequentialId(w, "creature_key", "creature_sequential", cs.getSequential());
            }
            case EmotionalState es -> {
                w.write("id", es.getId().toString());
                w.write("apathy_arausal", es.getApathy());
                w.write("curiosity_arausal", es.getCuriosity());
                w.write("fear_arausal", es.getFear());
                w.write("fertility_arausal", es.getFertility());
                w.write("hunger_arausal", es.getHunger());
                w.write("pain_arausal", es.getPain());
                w.write("sleep_arausal", es.getSleep());
                w.write("stress_arausal", es.getStress());
                w.write("tedium_arausal", es.getTedium());
            }
            case InternalDynamicState ids -> {
                w.write("id", ids.getId().toString());
                writeIfPresent(w, "changestimulusstate_id",
                        uuidOrNull(ids.getChangeStimulusState() != null ? ids.getChangeStimulusState().getId() : null));
                writeIfPresent(w, "finalemotionalstate_id",
                        uuidOrNull(ids.getFinalEmotionalState() != null ? ids.getFinalEmotionalState().getId() : null));
                writeIfPresent(w, "initialemotionalstate_id",
                        uuidOrNull(ids.getInitialEmotionalState() != null ? ids.getInitialEmotionalState().getId() : null));
            }
            case EyeState es -> {
                w.write("id", es.getId().toString());
                w.write("finalopening", es.getFinalOpening());
                w.write("finalstartangle", es.getFinalStartAngle());
                w.write("initialopening", es.getInitialOpening());
                w.write("initialstartangle", es.getInitialStartAngle());
                writeIfPresent(w, "changestimulusstate_id",
                        uuidOrNull(es.getChangeStimulusState() != null ? es.getChangeStimulusState().getId() : null));
            }
            case ObjectSeenState os -> {
                w.write("id", os.getId().toString());
                w.write("angle", os.getAngle());
                w.write("direction", os.getDirection());
                w.write("distance", os.getDistance());
                writeIfPresent(w, "type", DuckDBBackend.worldObjectTypeName(os.getType()));
                writeSequentialId(w, "key", "sequential", os.getObjectNumber());
                writeIfPresent(w, "changestimulusstate_id",
                        uuidOrNull(os.getChangeStimulusState() != null ? os.getChangeStimulusState().getId() : null));
            }
            case MouthInteractionState mis -> {
                w.write("id", mis.getId().toString());
                writeIfPresent(w, "objecttype", mis.getObjectType());
                writeIfPresent(w, "type", mis.getType() != null ? mis.getType().name() : null);
                writeSequentialId(w, "key", "sequential", mis.getObjectNumber());
                writeIfPresent(w, "changestimulusstate_id",
                        uuidOrNull(mis.getChangeStimulusState() != null ? mis.getChangeStimulusState().getId() : null));
            }
            case NoseState ns -> {
                w.write("id", ns.getId().toString());
                writeSequentialId(w, "key", "sequential", ns.getNose());
            }
            case ObjectSmeltState oss -> {
                w.write("id", oss.getId().toString());
                writeIfPresent(w, "objecttype", DuckDBBackend.worldObjectTypeName(oss.getObjectType()));
                writeIfPresent(w, "smelltype", oss.getSmellType() != null ? oss.getSmellType().name() : null);
                writeSequentialId(w, "key", "sequential", oss.getComponent());
                writeIfPresent(w, "changestimulusstate_id",
                        uuidOrNull(oss.getChangeStimulusState() != null ? oss.getChangeStimulusState().getId() : null));
            }
            case ChosenActionState cas -> {
                w.write("id", cas.getId().toString());
                writeIfPresent(w, "action", cas.getAction() != null ? cas.getAction().name() : null);
                writeIfPresent(w, "actionselectiontype", cas.getActionSelectionType() != null ? cas.getActionSelectionType().name() : null);
                w.write("inference_duration_ms", cas.getInferenceDurationMs());
                writeSequentialId(w, "key", "sequential", cas.getTarget());
                writeIfPresent(w, "changestimulusstate_id",
                        uuidOrNull(cas.getChangeStimulusState() != null ? cas.getChangeStimulusState().getId() : null));
            }
            case BodyState bs -> {
                w.write("id", bs.getId().toString());
                w.write("finalx", bs.getFinalX());
                w.write("finaly", bs.getFinalY());
                w.write("initialx", bs.getInitialX());
                w.write("initialy", bs.getInitialY());
                w.write("speed", bs.getSpeed());
                writeIfPresent(w, "stimulusstate_id",
                        uuidOrNull(bs.getStimulusState() != null ? bs.getStimulusState().getId() : null));
            }
            case BehaviouralEfficiencyState bes -> {
                w.write("id", bes.getId().toString());
                w.write("behaviouralefficiency", bes.getBehaviouralEfficiency());
                w.write("complextask", bes.isComplexTask());
                w.write("numberofobjects", bes.getNumberOfObjects());
                writeIfPresent(w, "changestimulusstate_id",
                        uuidOrNull(bes.getChangeStimulusState() != null ? bes.getChangeStimulusState().getId() : null));
            }
            case RegulationBatchStat rbs -> {
                w.write("id", rbs.getId().toString());
                w.write("batchsize", rbs.getBatchSize());
                w.write("drivestouchedmask", rbs.getDrivesTouchedMask());
                w.write("regulatingcount", rbs.getRegulatingCount());
                w.write("samedrivecollision", rbs.isSameDriveCollision());
                writeIfPresent(w, "changestimulusstate_id",
                        uuidOrNull(rbs.getChangeStimulusState() != null ? rbs.getChangeStimulusState().getId() : null));
            }
            case EngramState eg -> {
                w.write("id", eg.getId().toString());
                writeIfPresent(w, "action_type", eg.getActionType() != null ? eg.getActionType().name() : null);
                w.write("creature_key", eg.getCreatureKey());
                w.write("cycle_gap", eg.getCycleGap());
                w.write("eligibility", eg.getEligibility());
                w.write("emotion_delta", eg.getEmotionDelta());
                w.write("lay_cycle", eg.getLayCycle());
                w.write("reinforced_cycle", eg.getReinforcedCycle());
            }
            case SleepEpisodeState se -> {
                w.write("id", se.getId().toString());
                w.write("creature_key", se.getCreatureKey());
                w.write("duration_ticks", se.getDurationTicks());
                w.write("onset_cycle", se.getOnsetCycle());
                w.write("wake_cycle", se.getWakeCycle());
            }
            case ConsolidationEpisodeStat ce -> {
                w.write("id", ce.getId().toString());
                w.write("aborted", ce.isAborted());
                w.write("batches_completed", ce.getBatchesCompleted());
                w.write("creature_key", ce.getCreatureKey());
                w.write("engram_count", ce.getEngramCount());
                w.write("mean_eligibility", ce.getMeanEligibility());
                w.write("onset_cycle", ce.getOnsetCycle());
                w.write("std_eligibility", ce.getStdEligibility());
            }
            case ConsolidationBatchStat cb -> {
                w.write("id", cb.getId().toString());
                w.write("batch_index", cb.getBatchIndex());
                w.write("batch_size", cb.getBatchSize());
                w.write("creature_key", cb.getCreatureKey());
                w.write("loss", (double) cb.getLoss());
                w.write("onset_cycle", cb.getOnsetCycle());
            }
            case MemoryTraceStat mt -> {
                w.write("id", mt.getId().toString());
                w.write("creature_key", mt.getCreatureKey());
                w.write("engram_count", mt.getEngramCount());
                w.write("groups_consolidated", mt.getGroupsConsolidated());
                w.write("onset_cycle", mt.getOnsetCycle());
            }
            case ExpectancyState ex -> {
                w.write("id", ex.getId().toString());
                writeIfPresent(w, "action", ex.getAction() != null ? ex.getAction().name() : null);
                w.write("creature_key", ex.getCreatureKey());
                w.write("cycle", ex.getCycle());
                writeIfPresent(w, "drive", ex.getDrive());
                w.write("drive_level", ex.getDriveLevel());
                w.write("expected", ex.getExpected());
                writeIfPresent(w, "mode", ex.getMode());
                w.write("reward", ex.getReward());
                w.write("rpe", ex.getRpe());
                writeIfPresent(w, "target", ex.getTarget());
            }
            case NeuromodulatorStateLog nm -> {
                w.write("id", nm.getId().toString());
                w.write("circadian_phase", nm.getCircadianPhase());
                w.write("creature_key", nm.getCreatureKey());
                w.write("dopamine", nm.getDopamine());
                w.write("orexin", nm.getOrexin());
                w.write("seq", nm.getSeq());
                w.write("serotonin", nm.getSerotonin());
            }
            case EndocrineStateLog en -> {
                w.write("id", en.getId().toString());
                w.write("cortisol_tonic", en.getCortisolTonic());
                w.write("creature_key", en.getCreatureKey());
                w.write("seq", en.getSeq());
                w.write("stress_level", en.getStressLevel());
            }
            default -> throw new IllegalArgumentException("Unknown PersistenceState type: " + ps.getClass());
        }
    };

    /** Dehydrator equivalent of DuckDBBackend.bindSequentialId - writes two consecutive columns (or neither, if null - see writeIfPresent's javadoc). */
    private static void writeSequentialId(ValueWriter w, String keyCol, String seqCol, SequentialId id) {
        if (id != null) {
            w.write(keyCol, id.key);
            w.write(seqCol, id.sequential);
        }
    }
}
