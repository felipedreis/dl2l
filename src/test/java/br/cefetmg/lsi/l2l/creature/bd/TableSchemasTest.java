package br.cefetmg.lsi.l2l.creature.bd;

import br.cefetmg.lsi.l2l.common.SequentialId;
import br.cefetmg.lsi.l2l.world.FruitType;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Golden-parity test for {@link TableSchemas}. The 22 ported tables are checked against the
 * table/column-list shape they replaced (the former {@code ParquetBackend.TABLE_COLUMNS} +
 * {@code DEHYDRATOR} switch, {@code BDActor.tableFor()}'s switch, and
 * {@code PersistenceExtension.Impl.TABLES} - see docs/plans/arrow-ipc-write-path.md, W1). Their
 * expected shape below is transcribed from that code (still visible in git history at the commit
 * before this file was added) - a change there should be treated as a real schema change, not a
 * routine test update.
 *
 * <p>Tables added since the port are listed separately in {@link #ADDED_SINCE_PORT} and appended
 * after the ported block, so "we deliberately added a table" and "the ported schema drifted" stay
 * distinguishable failures.
 */
class TableSchemasTest {

    /** The 22 tables transcribed from ParquetBackend. This block must never change. */
    private static final List<String> PORTED_TABLE_ORDER = List.of(
            "change_stimulus_state", "stimulus_state", "creature_state", "emotional_state",
            "internal_dynamic_state", "eye_state", "object_seen_state", "mouth_interactions_state",
            "nose_state", "object_smelt_state", "chosen_action_state", "body_state",
            "behavioural_efficiency_state", "regulation_batch_stat", "engram_state",
            "sleep_episode_state", "consolidation_episode_stat", "consolidation_batch_stat",
            "memory_trace_stat", "expectancy_state", "neuromodulator_state_log", "endocrine_state_log");

    /**
     * Tables added after the port, appended in {@code buildAll()} so the ported block above stays
     * contiguous. Growing this list is a deliberate schema change; changing
     * {@link #PORTED_TABLE_ORDER} is a regression.
     */
    private static final List<String> ADDED_SINCE_PORT = List.of(
            "action_probability_state", "memory_decision_state");

    private static final List<String> EXPECTED_TABLE_ORDER =
            Stream.concat(PORTED_TABLE_ORDER.stream(), ADDED_SINCE_PORT.stream()).toList();

    /** Non-"id" column names, in order, per table - mirrors ParquetBackend's old TABLE_COLUMNS. */
    private static final Map<String, List<String>> EXPECTED_COLUMNS = expectedColumns();

    private static Map<String, List<String>> expectedColumns() {
        Map<String, List<String>> t = new LinkedHashMap<>();
        t.put("change_stimulus_state", List.of("componentclass", "time", "key", "sequential"));
        t.put("stimulus_state", List.of("stimulusclass", "type", "componentkey", "stimulusseq",
                "changestimulusemitted_id", "changestimulusreceived_id"));
        t.put("creature_state", List.of("borntime", "deadtime", "gender", "father_key", "father_sequential",
                "mother_key", "mother_sequential", "creature_key", "creature_sequential"));
        t.put("emotional_state", List.of("apathy_arausal", "curiosity_arausal", "fear_arausal",
                "fertility_arausal", "hunger_arausal", "pain_arausal", "sleep_arausal", "stress_arausal",
                "tedium_arausal"));
        t.put("internal_dynamic_state", List.of("changestimulusstate_id", "finalemotionalstate_id",
                "initialemotionalstate_id"));
        t.put("eye_state", List.of("finalopening", "finalstartangle", "initialopening", "initialstartangle",
                "changestimulusstate_id"));
        t.put("object_seen_state", List.of("angle", "direction", "distance", "type", "key", "sequential",
                "changestimulusstate_id"));
        t.put("mouth_interactions_state", List.of("objecttype", "type", "key", "sequential",
                "changestimulusstate_id"));
        t.put("nose_state", List.of("key", "sequential"));
        t.put("object_smelt_state", List.of("objecttype", "smelltype", "key", "sequential",
                "changestimulusstate_id"));
        t.put("chosen_action_state", List.of("action", "actionselectiontype", "inference_duration_ms", "key",
                "sequential", "changestimulusstate_id"));
        t.put("body_state", List.of("finalx", "finaly", "initialx", "initialy", "speed", "stimulusstate_id"));
        t.put("behavioural_efficiency_state", List.of("behaviouralefficiency", "complextask", "numberofobjects",
                "perceivedobjects", "changestimulusstate_id"));
        t.put("regulation_batch_stat", List.of("batchsize", "drivestouchedmask", "regulatingcount",
                "samedrivecollision", "changestimulusstate_id"));
        t.put("engram_state", List.of("action_type", "creature_key", "cycle_gap", "eligibility", "emotion_delta",
                "lay_cycle", "reinforced_cycle"));
        t.put("sleep_episode_state", List.of("creature_key", "duration_ticks", "onset_cycle", "wake_cycle"));
        t.put("consolidation_episode_stat", List.of("aborted", "batches_completed", "creature_key",
                "engram_count", "mean_eligibility", "onset_cycle", "std_eligibility"));
        t.put("consolidation_batch_stat", List.of("batch_index", "batch_size", "creature_key", "loss",
                "onset_cycle"));
        t.put("memory_trace_stat", List.of("creature_key", "engram_count", "groups_consolidated", "onset_cycle"));
        t.put("expectancy_state", List.of("action", "creature_key", "cycle", "drive", "drive_level", "expected",
                "mode", "reward", "rpe", "target"));
        t.put("neuromodulator_state_log", List.of("circadian_phase", "creature_key", "dopamine", "orexin", "seq",
                "serotonin"));
        t.put("endocrine_state_log", List.of("cortisol_tonic", "creature_key", "seq", "stress_level"));
        // Added since the port (issue #84).
        t.put("action_probability_state", List.of("action", "creature_key", "cycle", "delta",
                "probability", "reinforced_action", "seq", "target", "time_ms"));
        t.put("memory_decision_state", List.of("candidates", "creature_key", "cycle",
                "decided", "engram_window", "object_type", "objects", "returned",
                "runnerup_score", "scored", "seq", "time_ms", "winning_score", "key", "sequential"));
        return t;
    }

    private static final Map<String, Class<?>> EXPECTED_STATE_CLASS = Map.ofEntries(
            Map.entry("change_stimulus_state", ChangeStimulusState.class),
            Map.entry("stimulus_state", StimulusState.class),
            Map.entry("creature_state", CreatureState.class),
            Map.entry("emotional_state", EmotionalState.class),
            Map.entry("internal_dynamic_state", InternalDynamicState.class),
            Map.entry("eye_state", EyeState.class),
            Map.entry("object_seen_state", ObjectSeenState.class),
            Map.entry("mouth_interactions_state", MouthInteractionState.class),
            Map.entry("nose_state", NoseState.class),
            Map.entry("object_smelt_state", ObjectSmeltState.class),
            Map.entry("chosen_action_state", ChosenActionState.class),
            Map.entry("body_state", BodyState.class),
            Map.entry("behavioural_efficiency_state", BehaviouralEfficiencyState.class),
            Map.entry("regulation_batch_stat", RegulationBatchStat.class),
            Map.entry("engram_state", EngramState.class),
            Map.entry("sleep_episode_state", SleepEpisodeState.class),
            Map.entry("consolidation_episode_stat", ConsolidationEpisodeStat.class),
            Map.entry("consolidation_batch_stat", ConsolidationBatchStat.class),
            Map.entry("memory_trace_stat", MemoryTraceStat.class),
            Map.entry("expectancy_state", ExpectancyState.class),
            Map.entry("neuromodulator_state_log", NeuromodulatorStateLog.class),
            Map.entry("endocrine_state_log", EndocrineStateLog.class),
            Map.entry("action_probability_state", ActionProbabilityState.class),
            Map.entry("memory_decision_state", MemoryDecisionState.class));

    @Test
    void tablesAreThePorted22FollowedByAnyLaterAdditions() {
        List<String> actualOrder = TableSchemas.ALL.stream().map(TableSchemas.TableDef::table).toList();
        assertEquals(EXPECTED_TABLE_ORDER, actualOrder);
        assertEquals(PORTED_TABLE_ORDER, actualOrder.subList(0, PORTED_TABLE_ORDER.size()),
                "the 22 tables ported from ParquetBackend must stay a contiguous, unchanged prefix");
    }

    @Test
    void everyTableHasIdFirstAsAStringColumn() {
        for (TableSchemas.TableDef<?> def : TableSchemas.ALL) {
            TableSchemas.Column<?> first = def.columns().get(0);
            assertEquals("id", first.name(), "table " + def.table() + " must have id first");
            assertEquals(TableSchemas.ColType.STRING, first.type());
        }
    }

    @Test
    void everyTableColumnListMatchesTheOldParquetBackendColumnListExactly() {
        for (TableSchemas.TableDef<?> def : TableSchemas.ALL) {
            List<String> expected = EXPECTED_COLUMNS.get(def.table());
            assertNotNull(expected, "no golden column list for table " + def.table());
            List<String> actual = def.columns().stream().skip(1).map(TableSchemas.Column::name).toList();
            assertEquals(expected, actual, "column mismatch for table " + def.table());
        }
    }

    @Test
    void everyTableIsKeyedByItsExpectedStateClass() {
        for (TableSchemas.TableDef<?> def : TableSchemas.ALL) {
            assertEquals(EXPECTED_STATE_CLASS.get(def.table()), def.stateClass());
        }
    }

    @Test
    void forStateDispatchesEveryConcreteTypeToItsTable() {
        assertEquals("change_stimulus_state", TableSchemas.forState(new ChangeStimulusState()).table());
        assertEquals("stimulus_state", TableSchemas.forState(new StimulusState()).table());
        assertEquals("creature_state", TableSchemas.forState(new CreatureState()).table());
        assertEquals("emotional_state", TableSchemas.forState(new EmotionalState()).table());
        assertEquals("internal_dynamic_state", TableSchemas.forState(new InternalDynamicState()).table());
        assertEquals("eye_state", TableSchemas.forState(new EyeState()).table());
        assertEquals("nose_state", TableSchemas.forState(new NoseState()).table());
        assertEquals("body_state", TableSchemas.forState(new BodyState()).table());
        assertEquals("engram_state", TableSchemas.forState(new EngramState()).table());
        assertEquals("sleep_episode_state", TableSchemas.forState(new SleepEpisodeState()).table());
        assertEquals("memory_trace_stat", TableSchemas.forState(new MemoryTraceStat()).table());
        assertEquals("consolidation_batch_stat", TableSchemas.forState(new ConsolidationBatchStat()).table());
        assertEquals("neuromodulator_state_log", TableSchemas.forState(new NeuromodulatorStateLog()).table());
        assertEquals("endocrine_state_log", TableSchemas.forState(new EndocrineStateLog()).table());
    }

    @Test
    void forStateThrowsOnAnUnknownPersistenceStateType() {
        PersistenceState unknown = new PersistenceState() {
        };
        assertThrows(IllegalArgumentException.class, () -> TableSchemas.forState(unknown));
    }

    // --- getter fidelity spot checks (null-safety, enum-to-name, SequentialId expansion) -----

    @SuppressWarnings("unchecked")
    private static Object getterValue(String table, String column, PersistenceState state) {
        TableSchemas.TableDef<PersistenceState> def =
                (TableSchemas.TableDef<PersistenceState>) TableSchemas.forState(state);
        for (TableSchemas.Column<PersistenceState> c : def.columns()) {
            if (c.name().equals(column)) return c.getter().apply(state);
        }
        throw new AssertionError("no such column: " + table + "." + column);
    }

    @Test
    void sequentialIdExpandsToTwoColumnsOrBothNullWhenAbsent() {
        ChangeStimulusState withId = new ChangeStimulusState(null, null, "cls", new SequentialId(7).next(), 42L);
        assertEquals(7L, getterValue("change_stimulus_state", "key", withId));
        assertEquals(1L, getterValue("change_stimulus_state", "sequential", withId));

        ChangeStimulusState withoutId = new ChangeStimulusState();
        assertNull(getterValue("change_stimulus_state", "key", withoutId));
        assertNull(getterValue("change_stimulus_state", "sequential", withoutId));
    }

    @Test
    void enumGetterReturnsNameOrNull() {
        ObjectSeenState withType = new ObjectSeenState();
        withType.setType(FruitType.RED_APPLE);
        assertEquals("RED_APPLE", getterValue("object_seen_state", "type", withType));

        ObjectSeenState withoutType = new ObjectSeenState();
        assertNull(getterValue("object_seen_state", "type", withoutType));
    }

    @Test
    void uuidReferenceGetterReturnsStringOrNullWhenTheReferencedObjectIsAbsent() {
        EyeState withRef = new EyeState();
        ChangeStimulusState ref = new ChangeStimulusState();
        withRef.setChangeStimulusState(ref);
        assertEquals(ref.getId().toString(), getterValue("eye_state", "changestimulusstate_id", withRef));

        EyeState withoutRef = new EyeState();
        assertNull(getterValue("eye_state", "changestimulusstate_id", withoutRef));
    }

    @Test
    void primitiveNumericAndBooleanFieldsAreNeverNull() {
        CreatureState cs = new CreatureState(new SequentialId(3), true);
        assertEquals(0L, getterValue("creature_state", "borntime", cs));
        assertEquals(true, getterValue("creature_state", "gender", cs));
    }

    @Test
    void floatLossFieldIsWidenedToDouble() {
        ConsolidationBatchStat cb = new ConsolidationBatchStat(1, 2, 3, 4, 0.5f);
        Object loss = getterValue("consolidation_batch_stat", "loss", cb);
        assertInstanceOf(Double.class, loss);
        assertEquals(0.5, (double) loss, 1e-9);
    }
}
