package br.cefetmg.lsi.l2l.creature.bd;

import br.cefetmg.lsi.l2l.common.SequentialId;
import br.cefetmg.lsi.l2l.world.WorldObjectType;
import org.duckdb.DuckDBAppender;
import org.duckdb.DuckDBConnection;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@link PersistenceBackend} backed by an embedded DuckDB (not Postgres - see
 * docs/plans/parquet-write-path.md: a client-server RDBMS was itself the bottleneck for
 * this append-only telemetry workload, confirmed by a CCAD OOM crash under sustained load).
 *
 * <p>Every entity's surrogate id is a client-assigned {@link java.util.UUID} (see each
 * entity class), so - unlike the old JPA design - insert order never matters and no
 * generated-id round trip is needed.
 */
public class DuckDBBackend implements PersistenceBackend {

    private final Connection conn;
    private final Path rawDumpDir;

    /**
     * One long-lived {@link DuckDBAppender} per non-upsert table (everything except
     * {@link #UPSERT_TABLES}) - CONFIRMED LIVE that {@link PreparedStatement#executeBatch()}
     * against DuckDB is not viable for this workload (a local diagnostic hung with a single
     * {@code executeBatch()} call burning 76s of CPU time on a 5-minute, 1-creature run;
     * {@code jstack} caught {@code l2l-bd-dispatcher} stuck inside
     * {@code DuckDBPreparedStatement.executeBatch()}). The Appender API is DuckDB's documented
     * bulk-load path and is what {@link #UPSERT_TABLES} can't use (no {@code ON CONFLICT}
     * support), so those three tables stay on {@link PreparedStatement}. See
     * docs/plans/parquet-write-path.md.
     *
     * <p>CONFIRMED LIVE, separately: even after moving to Appender, throughput stayed
     * pathological (~150 states/sec, worse than the old Postgres path) - isolated benchmarking
     * (see docs/plans/parquet-write-path.md's validation log) showed plain DuckDB
     * INSERT/executeBatch/Appender are all individually fast in isolation, `ON CONFLICT DO
     * UPDATE` alone is real but partial overhead (~9x slower than plain insert, still not
     * enough alone to explain the gap), and removing this class's own per-batch
     * {@code appender.flush()} calls (deferred to {@link #flush()}, an explicit request only)
     * made no measurable difference either - the root cause was never conclusively isolated.
     * {@link ParquetBackend} exists as a strategy alternative that sidesteps the DuckDB write
     * path entirely rather than continuing to chase this.
     */
    private final Map<String, DuckDBAppender> appenders = new HashMap<>();

    public DuckDBBackend(Connection conn, Path rawDumpDir) throws SQLException {
        this.conn = conn;
        this.rawDumpDir = rawDumpDir;
        DuckDBConnection duckConn = (DuckDBConnection) conn;
        for (String table : PersistenceExtension.Impl.TABLES) {
            if (!UPSERT_TABLES.contains(table)) {
                appenders.put(table, duckConn.createAppender("data", table));
            }
        }
    }

    @Override
    public void persistBatch(Map<String, List<PersistenceState>> byTable) throws SQLException {
        // Appenders don't participate in conn's transaction (a separate, lower-level
        // bulk-load API) and are deliberately NOT flushed here on every batch - see flush()'s
        // javadoc for why (flush() is expensive; deferred to only when a Flush is actually
        // requested, not every batch).
        conn.setAutoCommit(false);
        try {
            for (Map.Entry<String, List<PersistenceState>> e : byTable.entrySet()) {
                writeTable(e.getKey(), e.getValue());
            }
            conn.commit();
        } catch (SQLException ex) {
            conn.rollback();
            throw ex;
        }
    }

    /**
     * CONFIRMED LIVE: flushing every appender after every regular batch (the original design)
     * made persist_duration_seconds blow up to 4-7+ SECONDS per ~700-state batch (~150
     * states/sec) and getting worse over the run - DuckDBAppender.flush() is not the cheap
     * buffer-push its name suggests, it does real work. Appenders have their own internal
     * auto-flush threshold regardless of explicit flush() calls, so deferring flush() to only
     * here (an actual Flush request, not every batch) doesn't risk unbounded appender-side
     * buffering - it just stops paying flush's cost dozens of times per second for no
     * correctness benefit (removing it did NOT fix the underlying slowness either - see
     * {@link #appenders}' javadoc). Mailbox FIFO ordering guarantees every PersistenceState
     * queued strictly before the Flush that triggers this call was already delivered and
     * committed in an earlier {@code persistBatch()} call.
     */
    @Override
    public void flush() throws SQLException {
        for (DuckDBAppender appender : appenders.values()) {
            appender.flush();
        }
    }

    /**
     * Dumps every raw table to its own Parquet file under {@code saveDir}/raw. Only ever
     * called via {@link DumpParquet}, always strictly after a {@link Flush} (see its javadoc),
     * so every write is guaranteed durably committed first. No joins/denormalization here by
     * design - scripts/dl2l_data/extract.py does that in Python against these files, unchanged
     * from what it already ran as SQL against Postgres - see docs/plans/parquet-write-path.md.
     */
    @Override
    public void dumpToParquet() throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            for (String table : PersistenceExtension.Impl.TABLES) {
                String outPath = rawDumpDir.resolve(table + ".parquet").toString().replace("'", "''");
                stmt.execute("COPY data." + table + " TO '" + outPath + "' (FORMAT PARQUET)");
            }
        }
    }

    @Override
    public void close() throws SQLException {
        for (DuckDBAppender appender : appenders.values()) {
            appender.close();
        }
        conn.close();
    }

    /**
     * Tables whose rows can legitimately be re-queued with the same id: {@code creature_state}
     * (CreatureActor writes it once at birth, again at death with deadTime now set - same
     * object/same id both times, see CreatureActor.kill()); {@code change_stimulus_state}
     * (the same instance is sometimes passed to two separate persist() calls rather than
     * bundled into one - see CreatureComponent.persist()'s javadoc - so it can land in two
     * different BDActor batches); {@code stimulus_state} (only reachable by walking a
     * ChangeStimulusState's cascade lists in expand() - if that parent is re-queued per
     * above, its already-expanded children get re-queued too). Every other entity is a
     * fresh object created and persisted exactly once, so its id can never legitimately
     * repeat.
     *
     * <p>MEASURED LIVE: applying ON CONFLICT DO UPDATE to every table (not just these three)
     * degrades BDActor's throughput over time - Postgres's speculative-insertion protocol for
     * ON CONFLICT costs meaningfully more than a plain INSERT even on the ~99.99% of rows that
     * never actually conflict, and that per-row tax compounds as the tables grow (a single
     * long-lived creature's cognitive-cycle rate crashed from ~1400 Hz to ~80 Hz over a few
     * minutes with backlog growing unboundedly, before this fix). Restricting the upsert path
     * to just the tables that need it keeps the other ~19 (the large majority of write volume)
     * on plain INSERT. See docs/plans/remove-jpa-persistence-layer.md.
     *
     * <p>Declared BEFORE INSERT_SQL below - static fields initialize in declaration order, and
     * buildInsertSql() (called by INSERT_SQL's initializer) reads this set, so the reverse
     * order NPEs (CONFIRMED LIVE) at class-init time.
     */
    private static final Set<String> UPSERT_TABLES =
            Set.of("creature_state", "change_stimulus_state", "stimulus_state");

    private static final Map<String, String> INSERT_SQL = buildInsertSql();

    private static Map<String, String> buildInsertSql() {
        Map<String, String[]> columnsByTable = new LinkedHashMap<>();
        columnsByTable.put("change_stimulus_state",
                new String[]{"componentclass", "time", "key", "sequential"});
        columnsByTable.put("stimulus_state",
                new String[]{"stimulusclass", "type", "componentkey", "stimulusseq",
                        "changestimulusemitted_id", "changestimulusreceived_id"});
        columnsByTable.put("creature_state",
                new String[]{"borntime", "deadtime", "gender", "father_key", "father_sequential",
                        "mother_key", "mother_sequential", "creature_key", "creature_sequential"});
        columnsByTable.put("emotional_state",
                new String[]{"apathy_arausal", "curiosity_arausal", "fear_arausal", "fertility_arausal",
                        "hunger_arausal", "pain_arausal", "sleep_arausal", "stress_arausal", "tedium_arausal"});
        columnsByTable.put("internal_dynamic_state",
                new String[]{"changestimulusstate_id", "finalemotionalstate_id", "initialemotionalstate_id"});
        columnsByTable.put("eye_state",
                new String[]{"finalopening", "finalstartangle", "initialopening", "initialstartangle",
                        "changestimulusstate_id"});
        columnsByTable.put("object_seen_state",
                new String[]{"angle", "direction", "distance", "type", "key", "sequential",
                        "changestimulusstate_id"});
        columnsByTable.put("mouth_interactions_state",
                new String[]{"objecttype", "type", "key", "sequential", "changestimulusstate_id"});
        columnsByTable.put("nose_state", new String[]{"key", "sequential"});
        columnsByTable.put("object_smelt_state",
                new String[]{"objecttype", "smelltype", "key", "sequential", "changestimulusstate_id"});
        columnsByTable.put("chosen_action_state",
                new String[]{"action", "actionselectiontype", "inference_duration_ms", "key", "sequential",
                        "changestimulusstate_id"});
        columnsByTable.put("body_state",
                new String[]{"finalx", "finaly", "initialx", "initialy", "speed", "stimulusstate_id"});
        columnsByTable.put("behavioural_efficiency_state",
                new String[]{"behaviouralefficiency", "complextask", "numberofobjects", "changestimulusstate_id"});
        columnsByTable.put("regulation_batch_stat",
                new String[]{"batchsize", "drivestouchedmask", "regulatingcount", "samedrivecollision",
                        "changestimulusstate_id"});
        columnsByTable.put("engram_state",
                new String[]{"action_type", "creature_key", "cycle_gap", "eligibility", "emotion_delta",
                        "lay_cycle", "reinforced_cycle"});
        columnsByTable.put("sleep_episode_state",
                new String[]{"creature_key", "duration_ticks", "onset_cycle", "wake_cycle"});
        columnsByTable.put("consolidation_episode_stat",
                new String[]{"aborted", "batches_completed", "creature_key", "engram_count",
                        "mean_eligibility", "onset_cycle", "std_eligibility"});
        columnsByTable.put("consolidation_batch_stat",
                new String[]{"batch_index", "batch_size", "creature_key", "loss", "onset_cycle"});
        columnsByTable.put("memory_trace_stat",
                new String[]{"creature_key", "engram_count", "groups_consolidated", "onset_cycle"});
        columnsByTable.put("expectancy_state",
                new String[]{"action", "creature_key", "cycle", "drive", "drive_level", "expected", "mode",
                        "reward", "rpe", "target"});
        columnsByTable.put("neuromodulator_state_log",
                new String[]{"circadian_phase", "creature_key", "dopamine", "orexin", "seq", "serotonin"});
        columnsByTable.put("endocrine_state_log",
                new String[]{"cortisol_tonic", "creature_key", "seq", "stress_level"});

        Map<String, String> sql = new LinkedHashMap<>();
        for (Map.Entry<String, String[]> e : columnsByTable.entrySet()) {
            sql.put(e.getKey(), insertSql(e.getKey(), e.getValue()));
        }
        return sql;
    }

    private static String insertSql(String table, String[] columns) {
        StringBuilder cols = new StringBuilder("id");
        StringBuilder placeholders = new StringBuilder("?");
        StringBuilder updateSet = new StringBuilder();
        for (String c : columns) {
            cols.append(", ").append(c);
            placeholders.append(", ?");
            if (updateSet.length() > 0) updateSet.append(", ");
            updateSet.append(c).append(" = EXCLUDED.").append(c);
        }
        String base = "INSERT INTO data." + table + " (" + cols + ") VALUES (" + placeholders + ")";
        return UPSERT_TABLES.contains(table)
                ? base + " ON CONFLICT (id) DO UPDATE SET " + updateSet
                : base;
    }

    /**
     * {@link #UPSERT_TABLES} need {@code ON CONFLICT}, which the Appender API doesn't support,
     * so those three stay on {@link PreparedStatement}; every other table uses its long-lived
     * {@link DuckDBAppender} (see {@link #appenders}' javadoc for why).
     */
    private void writeTable(String table, List<PersistenceState> rows) throws SQLException {
        if (UPSERT_TABLES.contains(table)) {
            try (PreparedStatement stmt = conn.prepareStatement(INSERT_SQL.get(table))) {
                for (PersistenceState ps : rows) {
                    bind(stmt, table, ps);
                    stmt.addBatch();
                }
                stmt.executeBatch();
            }
        } else {
            DuckDBAppender appender = appenders.get(table);
            for (PersistenceState ps : rows) {
                appender.beginRow();
                appendRow(appender, ps);
                appender.endRow();
            }
        }
    }

    /** Column order in every branch below must exactly match INSERT_SQL's column list. */
    private void bind(PreparedStatement s, String table, PersistenceState ps) throws SQLException {
        switch (ps) {
            case ChangeStimulusState css -> {
                s.setObject(1, css.getId());
                s.setString(2, css.getComponentClass());
                s.setLong(3, css.getTime());
                bindSequentialId(s, 4, css.getComponentID());
            }
            case StimulusState ss -> {
                s.setObject(1, ss.getId());
                s.setString(2, ss.getStimulusClass());
                s.setString(3, ss.getType() != null ? ss.getType().name() : null);
                bindSequentialId(s, 4, ss.getStimulusId());
                setUuidOrNull(s, 6, ss.getChangeStimulusEmitted() != null ? ss.getChangeStimulusEmitted().getId() : null);
                setUuidOrNull(s, 7, ss.getChangeStimulusReceived() != null ? ss.getChangeStimulusReceived().getId() : null);
            }
            case CreatureState cs -> {
                s.setObject(1, cs.getId());
                s.setLong(2, cs.getBornTime());
                s.setLong(3, cs.getDeadTime());
                s.setBoolean(4, cs.isGender());
                bindSequentialId(s, 5, cs.getFatherState());
                bindSequentialId(s, 7, cs.getMotherState());
                bindSequentialId(s, 9, cs.getSequential());
            }
            case EmotionalState es -> {
                s.setObject(1, es.getId());
                s.setDouble(2, es.getApathy());
                s.setDouble(3, es.getCuriosity());
                s.setDouble(4, es.getFear());
                s.setDouble(5, es.getFertility());
                s.setDouble(6, es.getHunger());
                s.setDouble(7, es.getPain());
                s.setDouble(8, es.getSleep());
                s.setDouble(9, es.getStress());
                s.setDouble(10, es.getTedium());
            }
            case InternalDynamicState ids -> {
                s.setObject(1, ids.getId());
                setUuidOrNull(s, 2, ids.getChangeStimulusState() != null ? ids.getChangeStimulusState().getId() : null);
                setUuidOrNull(s, 3, ids.getFinalEmotionalState() != null ? ids.getFinalEmotionalState().getId() : null);
                setUuidOrNull(s, 4, ids.getInitialEmotionalState() != null ? ids.getInitialEmotionalState().getId() : null);
            }
            case EyeState es -> {
                s.setObject(1, es.getId());
                s.setDouble(2, es.getFinalOpening());
                s.setDouble(3, es.getFinalStartAngle());
                s.setDouble(4, es.getInitialOpening());
                s.setDouble(5, es.getInitialStartAngle());
                setUuidOrNull(s, 6, es.getChangeStimulusState() != null ? es.getChangeStimulusState().getId() : null);
            }
            case ObjectSeenState os -> {
                s.setObject(1, os.getId());
                s.setDouble(2, os.getAngle());
                s.setDouble(3, os.getDirection());
                s.setDouble(4, os.getDistance());
                s.setString(5, worldObjectTypeName(os.getType()));
                bindSequentialId(s, 6, os.getObjectNumber());
                setUuidOrNull(s, 8, os.getChangeStimulusState() != null ? os.getChangeStimulusState().getId() : null);
            }
            case MouthInteractionState mis -> {
                s.setObject(1, mis.getId());
                s.setString(2, mis.getObjectType());
                s.setString(3, mis.getType() != null ? mis.getType().name() : null);
                bindSequentialId(s, 4, mis.getObjectNumber());
                setUuidOrNull(s, 6, mis.getChangeStimulusState() != null ? mis.getChangeStimulusState().getId() : null);
            }
            case NoseState ns -> {
                s.setObject(1, ns.getId());
                bindSequentialId(s, 2, ns.getNose());
            }
            case ObjectSmeltState oss -> {
                s.setObject(1, oss.getId());
                s.setString(2, worldObjectTypeName(oss.getObjectType()));
                s.setString(3, oss.getSmellType() != null ? oss.getSmellType().name() : null);
                bindSequentialId(s, 4, oss.getComponent());
                setUuidOrNull(s, 6, oss.getChangeStimulusState() != null ? oss.getChangeStimulusState().getId() : null);
            }
            case ChosenActionState cas -> {
                s.setObject(1, cas.getId());
                s.setString(2, cas.getAction() != null ? cas.getAction().name() : null);
                s.setString(3, cas.getActionSelectionType() != null ? cas.getActionSelectionType().name() : null);
                s.setLong(4, cas.getInferenceDurationMs());
                bindSequentialId(s, 5, cas.getTarget());
                setUuidOrNull(s, 7, cas.getChangeStimulusState() != null ? cas.getChangeStimulusState().getId() : null);
            }
            case BodyState bs -> {
                s.setObject(1, bs.getId());
                s.setDouble(2, bs.getFinalX());
                s.setDouble(3, bs.getFinalY());
                s.setDouble(4, bs.getInitialX());
                s.setDouble(5, bs.getInitialY());
                s.setDouble(6, bs.getSpeed());
                setUuidOrNull(s, 7, bs.getStimulusState() != null ? bs.getStimulusState().getId() : null);
            }
            case BehaviouralEfficiencyState bes -> {
                s.setObject(1, bes.getId());
                s.setDouble(2, bes.getBehaviouralEfficiency());
                s.setBoolean(3, bes.isComplexTask());
                s.setInt(4, bes.getNumberOfObjects());
                setUuidOrNull(s, 5, bes.getChangeStimulusState() != null ? bes.getChangeStimulusState().getId() : null);
            }
            case RegulationBatchStat rbs -> {
                s.setObject(1, rbs.getId());
                s.setInt(2, rbs.getBatchSize());
                s.setInt(3, rbs.getDrivesTouchedMask());
                s.setInt(4, rbs.getRegulatingCount());
                s.setBoolean(5, rbs.isSameDriveCollision());
                setUuidOrNull(s, 6, rbs.getChangeStimulusState() != null ? rbs.getChangeStimulusState().getId() : null);
            }
            case EngramState eg -> {
                s.setObject(1, eg.getId());
                s.setString(2, eg.getActionType() != null ? eg.getActionType().name() : null);
                s.setLong(3, eg.getCreatureKey());
                s.setLong(4, eg.getCycleGap());
                s.setDouble(5, eg.getEligibility());
                s.setDouble(6, eg.getEmotionDelta());
                s.setLong(7, eg.getLayCycle());
                s.setLong(8, eg.getReinforcedCycle());
            }
            case SleepEpisodeState se -> {
                s.setObject(1, se.getId());
                s.setLong(2, se.getCreatureKey());
                s.setInt(3, se.getDurationTicks());
                s.setLong(4, se.getOnsetCycle());
                s.setLong(5, se.getWakeCycle());
            }
            case ConsolidationEpisodeStat ce -> {
                s.setObject(1, ce.getId());
                s.setBoolean(2, ce.isAborted());
                s.setInt(3, ce.getBatchesCompleted());
                s.setLong(4, ce.getCreatureKey());
                s.setInt(5, ce.getEngramCount());
                s.setDouble(6, ce.getMeanEligibility());
                s.setLong(7, ce.getOnsetCycle());
                s.setDouble(8, ce.getStdEligibility());
            }
            case ConsolidationBatchStat cb -> {
                s.setObject(1, cb.getId());
                s.setInt(2, cb.getBatchIndex());
                s.setInt(3, cb.getBatchSize());
                s.setLong(4, cb.getCreatureKey());
                s.setFloat(5, cb.getLoss());
                s.setLong(6, cb.getOnsetCycle());
            }
            case MemoryTraceStat mt -> {
                s.setObject(1, mt.getId());
                s.setLong(2, mt.getCreatureKey());
                s.setInt(3, mt.getEngramCount());
                s.setInt(4, mt.getGroupsConsolidated());
                s.setLong(5, mt.getOnsetCycle());
            }
            case ExpectancyState ex -> {
                s.setObject(1, ex.getId());
                s.setString(2, ex.getAction() != null ? ex.getAction().name() : null);
                s.setLong(3, ex.getCreatureKey());
                s.setLong(4, ex.getCycle());
                s.setString(5, ex.getDrive());
                s.setDouble(6, ex.getDriveLevel());
                s.setDouble(7, ex.getExpected());
                s.setString(8, ex.getMode());
                s.setDouble(9, ex.getReward());
                s.setDouble(10, ex.getRpe());
                s.setString(11, ex.getTarget());
            }
            case NeuromodulatorStateLog nm -> {
                s.setObject(1, nm.getId());
                s.setDouble(2, nm.getCircadianPhase());
                s.setLong(3, nm.getCreatureKey());
                s.setDouble(4, nm.getDopamine());
                s.setDouble(5, nm.getOrexin());
                s.setLong(6, nm.getSeq());
                s.setDouble(7, nm.getSerotonin());
            }
            case EndocrineStateLog en -> {
                s.setObject(1, en.getId());
                s.setDouble(2, en.getCortisolTonic());
                s.setLong(3, en.getCreatureKey());
                s.setLong(4, en.getSeq());
                s.setDouble(5, en.getStressLevel());
            }
            default -> throw new IllegalArgumentException("Unknown PersistenceState type: " + ps.getClass());
        }
    }

    /**
     * Appender equivalent of {@link #bind}, for every table not in {@link #UPSERT_TABLES}.
     * Column order (after {@code id}, appended first) must exactly match each table's
     * {@code CREATE TABLE} order in {@link PersistenceExtension.Impl}'s schema DDL - same
     * order {@link #bind}'s cases already use, just without explicit indices (the Appender
     * has no random access, only sequential {@code append} calls per row).
     */
    private void appendRow(DuckDBAppender a, PersistenceState ps) throws SQLException {
        switch (ps) {
            case EmotionalState es -> {
                a.append(es.getId());
                a.append(es.getApathy());
                a.append(es.getCuriosity());
                a.append(es.getFear());
                a.append(es.getFertility());
                a.append(es.getHunger());
                a.append(es.getPain());
                a.append(es.getSleep());
                a.append(es.getStress());
                a.append(es.getTedium());
            }
            case InternalDynamicState ids -> {
                a.append(ids.getId());
                appendUuidOrNull(a, ids.getChangeStimulusState() != null ? ids.getChangeStimulusState().getId() : null);
                appendUuidOrNull(a, ids.getFinalEmotionalState() != null ? ids.getFinalEmotionalState().getId() : null);
                appendUuidOrNull(a, ids.getInitialEmotionalState() != null ? ids.getInitialEmotionalState().getId() : null);
            }
            case EyeState es -> {
                a.append(es.getId());
                a.append(es.getFinalOpening());
                a.append(es.getFinalStartAngle());
                a.append(es.getInitialOpening());
                a.append(es.getInitialStartAngle());
                appendUuidOrNull(a, es.getChangeStimulusState() != null ? es.getChangeStimulusState().getId() : null);
            }
            case ObjectSeenState os -> {
                a.append(os.getId());
                a.append(os.getAngle());
                a.append(os.getDirection());
                a.append(os.getDistance());
                a.append(worldObjectTypeName(os.getType()));
                appendSequentialId(a, os.getObjectNumber());
                appendUuidOrNull(a, os.getChangeStimulusState() != null ? os.getChangeStimulusState().getId() : null);
            }
            case MouthInteractionState mis -> {
                a.append(mis.getId());
                a.append(mis.getObjectType());
                a.append(mis.getType() != null ? mis.getType().name() : null);
                appendSequentialId(a, mis.getObjectNumber());
                appendUuidOrNull(a, mis.getChangeStimulusState() != null ? mis.getChangeStimulusState().getId() : null);
            }
            case NoseState ns -> {
                a.append(ns.getId());
                appendSequentialId(a, ns.getNose());
            }
            case ObjectSmeltState oss -> {
                a.append(oss.getId());
                a.append(worldObjectTypeName(oss.getObjectType()));
                a.append(oss.getSmellType() != null ? oss.getSmellType().name() : null);
                appendSequentialId(a, oss.getComponent());
                appendUuidOrNull(a, oss.getChangeStimulusState() != null ? oss.getChangeStimulusState().getId() : null);
            }
            case ChosenActionState cas -> {
                a.append(cas.getId());
                a.append(cas.getAction() != null ? cas.getAction().name() : null);
                a.append(cas.getActionSelectionType() != null ? cas.getActionSelectionType().name() : null);
                a.append(cas.getInferenceDurationMs());
                appendSequentialId(a, cas.getTarget());
                appendUuidOrNull(a, cas.getChangeStimulusState() != null ? cas.getChangeStimulusState().getId() : null);
            }
            case BodyState bs -> {
                a.append(bs.getId());
                a.append(bs.getFinalX());
                a.append(bs.getFinalY());
                a.append(bs.getInitialX());
                a.append(bs.getInitialY());
                a.append(bs.getSpeed());
                appendUuidOrNull(a, bs.getStimulusState() != null ? bs.getStimulusState().getId() : null);
            }
            case BehaviouralEfficiencyState bes -> {
                a.append(bes.getId());
                a.append(bes.getBehaviouralEfficiency());
                a.append(bes.isComplexTask());
                a.append(bes.getNumberOfObjects());
                appendUuidOrNull(a, bes.getChangeStimulusState() != null ? bes.getChangeStimulusState().getId() : null);
            }
            case RegulationBatchStat rbs -> {
                a.append(rbs.getId());
                a.append(rbs.getBatchSize());
                a.append(rbs.getDrivesTouchedMask());
                a.append(rbs.getRegulatingCount());
                a.append(rbs.isSameDriveCollision());
                appendUuidOrNull(a, rbs.getChangeStimulusState() != null ? rbs.getChangeStimulusState().getId() : null);
            }
            case EngramState eg -> {
                a.append(eg.getId());
                a.append(eg.getActionType() != null ? eg.getActionType().name() : null);
                a.append(eg.getCreatureKey());
                a.append(eg.getCycleGap());
                a.append(eg.getEligibility());
                a.append(eg.getEmotionDelta());
                a.append(eg.getLayCycle());
                a.append(eg.getReinforcedCycle());
            }
            case SleepEpisodeState se -> {
                a.append(se.getId());
                a.append(se.getCreatureKey());
                a.append(se.getDurationTicks());
                a.append(se.getOnsetCycle());
                a.append(se.getWakeCycle());
            }
            case ConsolidationEpisodeStat ce -> {
                a.append(ce.getId());
                a.append(ce.isAborted());
                a.append(ce.getBatchesCompleted());
                a.append(ce.getCreatureKey());
                a.append(ce.getEngramCount());
                a.append(ce.getMeanEligibility());
                a.append(ce.getOnsetCycle());
                a.append(ce.getStdEligibility());
            }
            case ConsolidationBatchStat cb -> {
                a.append(cb.getId());
                a.append(cb.getBatchIndex());
                a.append(cb.getBatchSize());
                a.append(cb.getCreatureKey());
                a.append((double) cb.getLoss()); // schema column is `double` (see setFloat's sibling comment above)
                a.append(cb.getOnsetCycle());
            }
            case MemoryTraceStat mt -> {
                a.append(mt.getId());
                a.append(mt.getCreatureKey());
                a.append(mt.getEngramCount());
                a.append(mt.getGroupsConsolidated());
                a.append(mt.getOnsetCycle());
            }
            case ExpectancyState ex -> {
                a.append(ex.getId());
                a.append(ex.getAction() != null ? ex.getAction().name() : null);
                a.append(ex.getCreatureKey());
                a.append(ex.getCycle());
                a.append(ex.getDrive());
                a.append(ex.getDriveLevel());
                a.append(ex.getExpected());
                a.append(ex.getMode());
                a.append(ex.getReward());
                a.append(ex.getRpe());
                a.append(ex.getTarget());
            }
            case NeuromodulatorStateLog nm -> {
                a.append(nm.getId());
                a.append(nm.getCircadianPhase());
                a.append(nm.getCreatureKey());
                a.append(nm.getDopamine());
                a.append(nm.getOrexin());
                a.append(nm.getSeq());
                a.append(nm.getSerotonin());
            }
            case EndocrineStateLog en -> {
                a.append(en.getId());
                a.append(en.getCortisolTonic());
                a.append(en.getCreatureKey());
                a.append(en.getSeq());
                a.append(en.getStressLevel());
            }
            default -> throw new IllegalArgumentException("Unknown or upsert-table PersistenceState type for Appender: " + ps.getClass());
        }
    }

    /** Appender equivalent of {@link #bindSequentialId} - appends two consecutive columns. */
    private void appendSequentialId(DuckDBAppender a, SequentialId id) throws SQLException {
        if (id == null) {
            a.appendNull();
            a.appendNull();
        } else {
            a.append(id.key);
            a.append(id.sequential);
        }
    }

    /** Appender equivalent of {@link #setUuidOrNull}. */
    private void appendUuidOrNull(DuckDBAppender a, java.util.UUID id) throws SQLException {
        if (id == null) a.appendNull();
        else a.append(id);
    }

    static String worldObjectTypeName(WorldObjectType type) {
        return type != null ? type.name() : null;
    }

    /** Binds SequentialId.key/.sequential into two consecutive columns starting at index. */
    private void bindSequentialId(PreparedStatement s, int index, SequentialId id) throws SQLException {
        if (id == null) {
            s.setNull(index, Types.BIGINT);
            s.setNull(index + 1, Types.BIGINT);
        } else {
            s.setLong(index, id.key);
            s.setLong(index + 1, id.sequential);
        }
    }

    private void setUuidOrNull(PreparedStatement s, int index, java.util.UUID id) throws SQLException {
        if (id == null) s.setNull(index, Types.OTHER);
        else s.setObject(index, id);
    }
}
