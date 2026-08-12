package br.cefetmg.lsi.l2l.creature.bd;

import br.cefetmg.lsi.l2l.common.SequentialId;
import br.cefetmg.lsi.l2l.world.WorldObjectType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * Single declarative source of truth for the 22-table raw schema, replacing what were four
 * hand-synced copies (this file is the fifth, and last, place they're declared):
 * {@code ParquetBackend}'s {@code TABLE_COLUMNS} + its 460-line dehydrator switch,
 * {@code BDActor.tableFor()}'s 22-way switch, {@code PersistenceExtension.Impl.TABLES}, and
 * Python's {@code RAW_TABLES} in {@code scripts/dl2l_data/extract.py} - the last of those needs
 * no replacement at all, since Arrow IPC files are self-describing (extraction just globs
 * {@code *.arrow} and reads whatever tables are present).
 *
 * <p>Column lists/getters are ported verbatim from the former {@code ParquetBackend}
 * ({@code TABLE_COLUMNS} + {@code DEHYDRATOR}), including the two-column {@link SequentialId}
 * expansion (a {@code key}/{@code sequential} pair from one getter) and the null-safe UUID/enum
 * getters. A backend consumes {@link #ALL} (or looks a single table up via {@link #forState})
 * and never needs per-state-type code of its own - see {@link ArrowIpcBackend}.
 */
public final class TableSchemas {

    private TableSchemas() {
    }

    public enum ColType {STRING, INT32, INT64, DOUBLE, BOOLEAN}

    /** One column: its Arrow-ish type and how to pull its value (possibly null) out of a {@code T}. */
    public record Column<T extends PersistenceState>(String name, ColType type, Function<T, Object> getter) {
    }

    /** One table: the {@link PersistenceState} subtype it's populated from, and its columns ("id" always first). */
    public record TableDef<T extends PersistenceState>(String table, Class<T> stateClass, List<Column<T>> columns) {
    }

    /** Every table, in the same dependency-irrelevant order the old {@code PersistenceExtension.Impl.TABLES} used. */
    public static final List<TableDef<?>> ALL = buildAll();

    private static final Map<Class<?>, TableDef<?>> BY_CLASS = buildByClass();

    /** Looks up the {@link TableDef} for a given state's runtime type. */
    public static TableDef<?> forState(PersistenceState ps) {
        TableDef<?> def = BY_CLASS.get(ps.getClass());
        if (def == null) {
            throw new IllegalArgumentException("Unknown PersistenceState type: " + ps.getClass());
        }
        return def;
    }

    private static Map<Class<?>, TableDef<?>> buildByClass() {
        Map<Class<?>, TableDef<?>> byClass = new LinkedHashMap<>();
        for (TableDef<?> def : ALL) {
            byClass.put(def.stateClass(), def);
        }
        return byClass;
    }

    // --- helpers ------------------------------------------------------------------------

    private static <T extends PersistenceState> Column<T> col(String name, ColType type, Function<T, Object> getter) {
        return new Column<>(name, type, getter);
    }

    private static <T extends PersistenceState> TableDef<T> table(
            String name, Class<T> cls, Function<T, UUID> idGetter, List<Column<T>> columns) {
        List<Column<T>> withId = new ArrayList<>(columns.size() + 1);
        withId.add(col("id", ColType.STRING, ps -> uuidOrNull(idGetter.apply(ps))));
        withId.addAll(columns);
        return new TableDef<>(name, cls, List.copyOf(withId));
    }

    private static String uuidOrNull(UUID id) {
        return id == null ? null : id.toString();
    }

    private static String worldObjectTypeName(WorldObjectType type) {
        return type != null ? type.name() : null;
    }

    private static Long seqKey(SequentialId id) {
        return id == null ? null : id.key;
    }

    private static Long seqSequential(SequentialId id) {
        return id == null ? null : id.sequential;
    }

    /** Expands one {@link SequentialId}-typed field into its two flat columns ({@code keyCol}/{@code seqCol}). */
    private static <T extends PersistenceState> List<Column<T>> seqCols(
            String keyCol, String seqCol, Function<T, SequentialId> getter) {
        return List.of(
                col(keyCol, ColType.INT64, ps -> seqKey(getter.apply(ps))),
                col(seqCol, ColType.INT64, ps -> seqSequential(getter.apply(ps))));
    }

    // --- table definitions ---------------------------------------------------------------

    private static List<TableDef<?>> buildAll() {
        List<TableDef<?>> all = new ArrayList<>();

        all.add(table("change_stimulus_state", ChangeStimulusState.class, ChangeStimulusState::getId, concat(
                List.of(
                        col("componentclass", ColType.STRING, ChangeStimulusState::getComponentClass),
                        col("time", ColType.INT64, ChangeStimulusState::getTime)),
                seqCols("key", "sequential", ChangeStimulusState::getComponentID))));

        all.add(table("stimulus_state", StimulusState.class, StimulusState::getId, concat(
                List.of(
                        col("stimulusclass", ColType.STRING, StimulusState::getStimulusClass),
                        col("type", ColType.STRING, (StimulusState ss) -> ss.getType() != null ? ss.getType().name() : null)),
                seqCols("componentkey", "stimulusseq", StimulusState::getStimulusId),
                List.of(
                        col("changestimulusemitted_id", ColType.STRING, (StimulusState ss) ->
                                uuidOrNull(ss.getChangeStimulusEmitted() != null ? ss.getChangeStimulusEmitted().getId() : null)),
                        col("changestimulusreceived_id", ColType.STRING, (StimulusState ss) ->
                                uuidOrNull(ss.getChangeStimulusReceived() != null ? ss.getChangeStimulusReceived().getId() : null))))));

        all.add(table("creature_state", CreatureState.class, CreatureState::getId, concat(
                List.of(
                        col("borntime", ColType.INT64, CreatureState::getBornTime),
                        col("deadtime", ColType.INT64, CreatureState::getDeadTime),
                        col("gender", ColType.BOOLEAN, CreatureState::isGender)),
                seqCols("father_key", "father_sequential", CreatureState::getFatherState),
                seqCols("mother_key", "mother_sequential", CreatureState::getMotherState),
                seqCols("creature_key", "creature_sequential", CreatureState::getSequential))));

        all.add(table("emotional_state", EmotionalState.class, EmotionalState::getId, List.of(
                col("apathy_arausal", ColType.DOUBLE, EmotionalState::getApathy),
                col("curiosity_arausal", ColType.DOUBLE, EmotionalState::getCuriosity),
                col("fear_arausal", ColType.DOUBLE, EmotionalState::getFear),
                col("fertility_arausal", ColType.DOUBLE, EmotionalState::getFertility),
                col("hunger_arausal", ColType.DOUBLE, EmotionalState::getHunger),
                col("pain_arausal", ColType.DOUBLE, EmotionalState::getPain),
                col("sleep_arausal", ColType.DOUBLE, EmotionalState::getSleep),
                col("stress_arausal", ColType.DOUBLE, EmotionalState::getStress),
                col("tedium_arausal", ColType.DOUBLE, EmotionalState::getTedium))));

        all.add(table("internal_dynamic_state", InternalDynamicState.class, InternalDynamicState::getId, List.of(
                col("changestimulusstate_id", ColType.STRING, (InternalDynamicState ids) ->
                        uuidOrNull(ids.getChangeStimulusState() != null ? ids.getChangeStimulusState().getId() : null)),
                col("finalemotionalstate_id", ColType.STRING, (InternalDynamicState ids) ->
                        uuidOrNull(ids.getFinalEmotionalState() != null ? ids.getFinalEmotionalState().getId() : null)),
                col("initialemotionalstate_id", ColType.STRING, (InternalDynamicState ids) ->
                        uuidOrNull(ids.getInitialEmotionalState() != null ? ids.getInitialEmotionalState().getId() : null)))));

        all.add(table("eye_state", EyeState.class, EyeState::getId, List.of(
                col("finalopening", ColType.DOUBLE, EyeState::getFinalOpening),
                col("finalstartangle", ColType.DOUBLE, EyeState::getFinalStartAngle),
                col("initialopening", ColType.DOUBLE, EyeState::getInitialOpening),
                col("initialstartangle", ColType.DOUBLE, EyeState::getInitialStartAngle),
                col("changestimulusstate_id", ColType.STRING, (EyeState es) ->
                        uuidOrNull(es.getChangeStimulusState() != null ? es.getChangeStimulusState().getId() : null)))));

        all.add(table("object_seen_state", ObjectSeenState.class, ObjectSeenState::getId, concat(
                List.of(
                        col("angle", ColType.DOUBLE, ObjectSeenState::getAngle),
                        col("direction", ColType.DOUBLE, ObjectSeenState::getDirection),
                        col("distance", ColType.DOUBLE, ObjectSeenState::getDistance),
                        col("type", ColType.STRING, (ObjectSeenState os) -> worldObjectTypeName(os.getType()))),
                seqCols("key", "sequential", ObjectSeenState::getObjectNumber),
                List.of(
                        col("changestimulusstate_id", ColType.STRING, (ObjectSeenState os) ->
                                uuidOrNull(os.getChangeStimulusState() != null ? os.getChangeStimulusState().getId() : null))))));

        all.add(table("mouth_interactions_state", MouthInteractionState.class, MouthInteractionState::getId, concat(
                List.of(
                        col("objecttype", ColType.STRING, MouthInteractionState::getObjectType),
                        col("type", ColType.STRING, (MouthInteractionState mis) -> mis.getType() != null ? mis.getType().name() : null)),
                seqCols("key", "sequential", MouthInteractionState::getObjectNumber),
                List.of(
                        col("changestimulusstate_id", ColType.STRING, (MouthInteractionState mis) ->
                                uuidOrNull(mis.getChangeStimulusState() != null ? mis.getChangeStimulusState().getId() : null))))));

        all.add(table("nose_state", NoseState.class, NoseState::getId,
                seqCols("key", "sequential", NoseState::getNose)));

        all.add(table("object_smelt_state", ObjectSmeltState.class, ObjectSmeltState::getId, concat(
                List.of(
                        col("objecttype", ColType.STRING, (ObjectSmeltState oss) -> worldObjectTypeName(oss.getObjectType())),
                        col("smelltype", ColType.STRING, (ObjectSmeltState oss) -> oss.getSmellType() != null ? oss.getSmellType().name() : null)),
                seqCols("key", "sequential", ObjectSmeltState::getComponent),
                List.of(
                        col("changestimulusstate_id", ColType.STRING, (ObjectSmeltState oss) ->
                                uuidOrNull(oss.getChangeStimulusState() != null ? oss.getChangeStimulusState().getId() : null))))));

        all.add(table("chosen_action_state", ChosenActionState.class, ChosenActionState::getId, concat(
                List.of(
                        col("action", ColType.STRING, (ChosenActionState cas) -> cas.getAction() != null ? cas.getAction().name() : null),
                        col("actionselectiontype", ColType.STRING, (ChosenActionState cas) ->
                                cas.getActionSelectionType() != null ? cas.getActionSelectionType().name() : null),
                        col("inference_duration_ms", ColType.INT64, ChosenActionState::getInferenceDurationMs)),
                seqCols("key", "sequential", ChosenActionState::getTarget),
                List.of(
                        col("changestimulusstate_id", ColType.STRING, (ChosenActionState cas) ->
                                uuidOrNull(cas.getChangeStimulusState() != null ? cas.getChangeStimulusState().getId() : null))))));

        all.add(table("body_state", BodyState.class, BodyState::getId, List.of(
                col("finalx", ColType.DOUBLE, BodyState::getFinalX),
                col("finaly", ColType.DOUBLE, BodyState::getFinalY),
                col("initialx", ColType.DOUBLE, BodyState::getInitialX),
                col("initialy", ColType.DOUBLE, BodyState::getInitialY),
                col("speed", ColType.DOUBLE, BodyState::getSpeed),
                col("stimulusstate_id", ColType.STRING, (BodyState bs) ->
                        uuidOrNull(bs.getStimulusState() != null ? bs.getStimulusState().getId() : null)))));

        all.add(table("behavioural_efficiency_state", BehaviouralEfficiencyState.class, BehaviouralEfficiencyState::getId, List.of(
                col("behaviouralefficiency", ColType.DOUBLE, BehaviouralEfficiencyState::getBehaviouralEfficiency),
                col("complextask", ColType.BOOLEAN, BehaviouralEfficiencyState::isComplexTask),
                col("numberofobjects", ColType.INT32, BehaviouralEfficiencyState::getNumberOfObjects),
                col("perceivedobjects", ColType.INT32, BehaviouralEfficiencyState::getPerceivedObjects),
                col("changestimulusstate_id", ColType.STRING, (BehaviouralEfficiencyState bes) ->
                        uuidOrNull(bes.getChangeStimulusState() != null ? bes.getChangeStimulusState().getId() : null)))));

        all.add(table("regulation_batch_stat", RegulationBatchStat.class, RegulationBatchStat::getId, List.of(
                col("batchsize", ColType.INT32, RegulationBatchStat::getBatchSize),
                col("drivestouchedmask", ColType.INT32, RegulationBatchStat::getDrivesTouchedMask),
                col("regulatingcount", ColType.INT32, RegulationBatchStat::getRegulatingCount),
                col("samedrivecollision", ColType.BOOLEAN, RegulationBatchStat::isSameDriveCollision),
                col("changestimulusstate_id", ColType.STRING, (RegulationBatchStat rbs) ->
                        uuidOrNull(rbs.getChangeStimulusState() != null ? rbs.getChangeStimulusState().getId() : null)))));

        all.add(table("engram_state", EngramState.class, EngramState::getId, List.of(
                col("action_type", ColType.STRING, (EngramState eg) -> eg.getActionType() != null ? eg.getActionType().name() : null),
                col("creature_key", ColType.INT64, EngramState::getCreatureKey),
                col("cycle_gap", ColType.INT64, EngramState::getCycleGap),
                col("drive", ColType.STRING, EngramState::getDrive),
                col("drive_level", ColType.DOUBLE, EngramState::getDriveLevel),
                col("eligibility", ColType.DOUBLE, EngramState::getEligibility),
                col("emotion_delta", ColType.DOUBLE, EngramState::getEmotionDelta),
                col("lay_cycle", ColType.INT64, EngramState::getLayCycle),
                col("object_type", ColType.STRING, EngramState::getObjectType),
                col("reinforced_cycle", ColType.INT64, EngramState::getReinforcedCycle))));

        all.add(table("sleep_episode_state", SleepEpisodeState.class, SleepEpisodeState::getId, List.of(
                col("creature_key", ColType.INT64, SleepEpisodeState::getCreatureKey),
                col("duration_ticks", ColType.INT32, SleepEpisodeState::getDurationTicks),
                col("onset_cycle", ColType.INT64, SleepEpisodeState::getOnsetCycle),
                col("wake_cycle", ColType.INT64, SleepEpisodeState::getWakeCycle))));

        all.add(table("consolidation_episode_stat", ConsolidationEpisodeStat.class, ConsolidationEpisodeStat::getId, List.of(
                col("aborted", ColType.BOOLEAN, ConsolidationEpisodeStat::isAborted),
                col("batches_completed", ColType.INT32, ConsolidationEpisodeStat::getBatchesCompleted),
                col("creature_key", ColType.INT64, ConsolidationEpisodeStat::getCreatureKey),
                col("engram_count", ColType.INT32, ConsolidationEpisodeStat::getEngramCount),
                col("mean_eligibility", ColType.DOUBLE, ConsolidationEpisodeStat::getMeanEligibility),
                col("onset_cycle", ColType.INT64, ConsolidationEpisodeStat::getOnsetCycle),
                col("std_eligibility", ColType.DOUBLE, ConsolidationEpisodeStat::getStdEligibility))));

        all.add(table("consolidation_batch_stat", ConsolidationBatchStat.class, ConsolidationBatchStat::getId, List.of(
                col("batch_index", ColType.INT32, ConsolidationBatchStat::getBatchIndex),
                col("batch_size", ColType.INT32, ConsolidationBatchStat::getBatchSize),
                col("creature_key", ColType.INT64, ConsolidationBatchStat::getCreatureKey),
                col("loss", ColType.DOUBLE, (ConsolidationBatchStat cb) -> (double) cb.getLoss()),
                col("onset_cycle", ColType.INT64, ConsolidationBatchStat::getOnsetCycle))));

        all.add(table("memory_trace_stat", MemoryTraceStat.class, MemoryTraceStat::getId, List.of(
                col("creature_key", ColType.INT64, MemoryTraceStat::getCreatureKey),
                col("engram_count", ColType.INT32, MemoryTraceStat::getEngramCount),
                col("groups_consolidated", ColType.INT32, MemoryTraceStat::getGroupsConsolidated),
                col("onset_cycle", ColType.INT64, MemoryTraceStat::getOnsetCycle))));

        all.add(table("expectancy_state", ExpectancyState.class, ExpectancyState::getId, List.of(
                col("action", ColType.STRING, (ExpectancyState ex) -> ex.getAction() != null ? ex.getAction().name() : null),
                col("creature_key", ColType.INT64, ExpectancyState::getCreatureKey),
                col("cycle", ColType.INT64, ExpectancyState::getCycle),
                col("drive", ColType.STRING, ExpectancyState::getDrive),
                col("drive_level", ColType.DOUBLE, ExpectancyState::getDriveLevel),
                col("expected", ColType.DOUBLE, ExpectancyState::getExpected),
                col("mode", ColType.STRING, ExpectancyState::getMode),
                col("reward", ColType.DOUBLE, ExpectancyState::getReward),
                col("rpe", ColType.DOUBLE, ExpectancyState::getRpe),
                col("target", ColType.STRING, ExpectancyState::getTarget))));

        all.add(table("neuromodulator_state_log", NeuromodulatorStateLog.class, NeuromodulatorStateLog::getId, List.of(
                col("circadian_phase", ColType.DOUBLE, NeuromodulatorStateLog::getCircadianPhase),
                col("creature_key", ColType.INT64, NeuromodulatorStateLog::getCreatureKey),
                col("dopamine", ColType.DOUBLE, NeuromodulatorStateLog::getDopamine),
                col("orexin", ColType.DOUBLE, NeuromodulatorStateLog::getOrexin),
                col("seq", ColType.INT64, NeuromodulatorStateLog::getSeq),
                col("serotonin", ColType.DOUBLE, NeuromodulatorStateLog::getSerotonin))));

        all.add(table("endocrine_state_log", EndocrineStateLog.class, EndocrineStateLog::getId, List.of(
                col("cortisol_tonic", ColType.DOUBLE, EndocrineStateLog::getCortisolTonic),
                col("creature_key", ColType.INT64, EndocrineStateLog::getCreatureKey),
                col("seq", ColType.INT64, EndocrineStateLog::getSeq),
                col("stress_level", ColType.DOUBLE, EndocrineStateLog::getStressLevel))));

        // --- Added after the ParquetBackend port (issue #84). Appended rather than slotted in
        // next to their thematic neighbours so the 22 ported tables above stay a contiguous,
        // verbatim block - see TableSchemasTest, which golden-checks them separately.

        all.add(table("action_probability_state", ActionProbabilityState.class, ActionProbabilityState::getId, List.of(
                col("action", ColType.STRING, (ActionProbabilityState ap) ->
                        ap.getAction() != null ? ap.getAction().name() : null),
                col("creature_key", ColType.INT64, ActionProbabilityState::getCreatureKey),
                col("cycle", ColType.INT64, ActionProbabilityState::getCycle),
                col("delta", ColType.DOUBLE, ActionProbabilityState::getDelta),
                col("probability", ColType.DOUBLE, ActionProbabilityState::getProbability),
                col("reinforced_action", ColType.STRING, (ActionProbabilityState ap) ->
                        ap.getReinforcedAction() != null ? ap.getReinforcedAction().name() : null),
                col("seq", ColType.INT64, ActionProbabilityState::getSeq),
                col("target", ColType.STRING, ActionProbabilityState::getTarget),
                col("time_ms", ColType.INT64, ActionProbabilityState::getTimeMs))));

        all.add(table("memory_decision_state", MemoryDecisionState.class, MemoryDecisionState::getId, concat(
                List.of(
                        col("candidates", ColType.INT32, MemoryDecisionState::getCandidates),
                        col("creature_key", ColType.INT64, MemoryDecisionState::getCreatureKey),
                        col("cycle", ColType.INT64, MemoryDecisionState::getCycle),
                        col("decided", ColType.BOOLEAN, MemoryDecisionState::isDecided),
                        col("engram_window", ColType.INT32, MemoryDecisionState::getEngramWindow),
                        col("object_type", ColType.STRING, MemoryDecisionState::getObjectType),
                        col("objects", ColType.INT32, MemoryDecisionState::getObjects),
                        col("returned", ColType.INT32, MemoryDecisionState::getReturned),
                        col("runnerup_score", ColType.DOUBLE, MemoryDecisionState::getRunnerUpScore),
                        col("scored", ColType.INT32, MemoryDecisionState::getScored),
                        col("seq", ColType.INT64, MemoryDecisionState::getSeq),
                        col("time_ms", ColType.INT64, MemoryDecisionState::getTimeMs),
                        col("winning_score", ColType.DOUBLE, MemoryDecisionState::getWinningScore)),
                seqCols("key", "sequential", MemoryDecisionState::getTarget))));

        return List.copyOf(all);
    }

    @SafeVarargs
    private static <T extends PersistenceState> List<Column<T>> concat(List<Column<T>>... parts) {
        List<Column<T>> merged = new ArrayList<>();
        for (List<Column<T>> part : parts) {
            merged.addAll(part);
        }
        return List.copyOf(merged);
    }
}
