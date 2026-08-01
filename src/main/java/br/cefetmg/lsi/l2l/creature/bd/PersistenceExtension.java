package br.cefetmg.lsi.l2l.creature.bd;

import akka.actor.AbstractExtensionId;
import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.actor.CoordinatedShutdown;
import akka.actor.ExtendedActorSystem;
import akka.actor.Extension;
import akka.actor.Props;
import akka.pattern.Patterns;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;

/**
 * Akka Extension owning the single {@link BDActor} that persists all creature state for
 * this JVM.
 *
 * <p>History: every creature component originally opened its own JPA {@code EntityManagerFactory}
 * (~70-100 per holder), then (issue #79) JPA/EclipseLink was removed entirely in favor of
 * raw JDBC against Postgres with a small sharded {@code BDActor} pool - client-assigned
 * {@link java.util.UUID} ids meant no generated-id sequence to serialize on. That still
 * OOM'd under sustained real load on CCAD: a single BDActor/shard's Postgres write ceiling
 * (~18-20K states/sec) was below the sustained generation rate of just a few
 * concurrently-alive creatures, and nothing bounded the resulting backlog - see
 * docs/plans/parquet-write-path.md.
 *
 * <p>Postgres itself was the wrong tool for this workload (append-only telemetry, never
 * queried mid-run, already shipped downstream as Parquet) - a client-server RDBMS pays
 * network round-trip + WAL/MVCC/lock overhead on every write regardless of batching.
 * The actual write mechanics are now a pluggable {@link PersistenceBackend} strategy,
 * selected via the {@code PERSISTENCE_BACKEND} env var ({@code duckdb} default, or
 * {@code parquet}) - see {@link DuckDBBackend}/{@link ParquetBackend}'s own javadoc for
 * why both exist. A single {@link BDActor}/backend instance (no more sharding - not yet
 * proven necessary; revisit only if a local diagnostic shows otherwise).
 *
 * <p>Usage: {@code PersistenceExtension.of(context().system()).configure(saveDir)} once from
 * {@link br.cefetmg.lsi.l2l.cluster.Holder#preStart()}, before any creature is spawned (mirrors
 * {@link br.cefetmg.lsi.l2l.cluster.SimulationSettingsExtension}'s same pattern); then
 * {@code PersistenceExtension.of(context().system()).bdActor()} everywhere else.
 */
public class PersistenceExtension extends AbstractExtensionId<PersistenceExtension.Impl> {

    public static final PersistenceExtension Id = new PersistenceExtension();

    public static Impl of(ActorSystem system) {
        return system.registerExtension(Id);
    }

    @Override
    public Impl createExtension(ExtendedActorSystem system) {
        return new Impl(system);
    }

    public static class Impl implements Extension {

        private enum BackendKind { DUCKDB, PARQUET }

        private final ExtendedActorSystem system;

        private volatile BackendKind backendKind;
        private volatile Connection conn;
        private volatile ActorRef bdActor;
        private volatile Path rawDumpDir;

        Impl(ExtendedActorSystem system) {
            this.system = system;
        }

        /**
         * Resolves which {@link PersistenceBackend} to use (opening the embedded DuckDB file
         * + issuing the schema if that's the chosen one), sets up {@code saveDir}/raw, and
         * starts the single {@link BDActor}. Idempotent - safe to call more than once (only
         * the first call takes effect), so callers don't need to coordinate who calls it
         * first. Must complete before any creature is spawned, since
         * {@link CreatureActor}/component actors resolve {@link #bdActor()} in their own
         * {@code preStart()}.
         */
        public synchronized void configure(String saveDir) {
            if (bdActor != null) return;
            try {
                Path dir = Path.of(saveDir);
                Files.createDirectories(dir);
                this.rawDumpDir = dir.resolve("raw");
                Files.createDirectories(rawDumpDir);

                String env = System.getenv("PERSISTENCE_BACKEND");
                this.backendKind = "parquet".equalsIgnoreCase(env) ? BackendKind.PARQUET : BackendKind.DUCKDB;

                if (backendKind == BackendKind.DUCKDB) {
                    this.conn = DriverManager.getConnection("jdbc:duckdb:" + dir.resolve("dl2l.duckdb"));
                    try (Statement stmt = conn.createStatement()) {
                        for (String ddlStatement : SCHEMA_DDL) {
                            stmt.execute(ddlStatement);
                        }
                    }
                }
            } catch (SQLException | IOException e) {
                throw new RuntimeException("PersistenceExtension failed to open its backend", e);
            }

            this.bdActor = system.actorOf(
                    Props.create(BDActor.class).withDispatcher("bd-dispatcher"), "bd");

            // Defense-in-depth for shutdown paths that bypass Holder's own two drain points
            // (handleRemoveObject/handleFinish - see docs/plans/bdactor-async-persistence-with-drain.md
            // §4): a crashed/killed holder JVM, docker-compose down/SIGTERM, or a cluster Down
            // event. PhaseBeforeActorSystemTerminate runs after cluster shutdown and before
            // ActorSystem termination, on every realistic shutdown path.
            CoordinatedShutdown.get(system).addTask(
                    CoordinatedShutdown.PhaseBeforeActorSystemTerminate(), "drain-bdactor",
                    () -> Patterns.ask(bdActor, new Flush(), Duration.ofSeconds(30))
                            .thenApply(v -> akka.Done.done())
                            .exceptionally(ex -> {
                                String msg = ex.getMessage();
                                if (msg != null && msg.contains("already been terminated")) {
                                    return akka.Done.done();
                                }
                                throw new RuntimeException(ex);
                            }));
        }

        public ActorRef bdActor() {
            if (bdActor == null) {
                throw new IllegalStateException(
                        "PersistenceExtension.configure(saveDir) must be called (from Holder.preStart()) "
                                + "before any creature persists state");
            }
            return bdActor;
        }

        /**
         * Constructs the {@link PersistenceBackend} chosen by {@code configure()}'s
         * {@code PERSISTENCE_BACKEND} env var. Called once, from {@link BDActor}'s own
         * constructor (single instance per JVM - see class javadoc).
         */
        PersistenceBackend newBackend() throws Exception {
            return switch (backendKind) {
                case DUCKDB -> new DuckDBBackend(conn, rawDumpDir);
                case PARQUET -> new ParquetBackend(rawDumpDir);
            };
        }

        /**
         * Every table this schema defines, in dependency-irrelevant order (no FK constraints -
         * see config/schema.sql's header). Kept alongside the DDL so {@link BDActor#dumpToParquet()}
         * can iterate the same list without duplicating it.
         */
        static final String[] TABLES = {
                "change_stimulus_state", "stimulus_state", "creature_state", "emotional_state",
                "internal_dynamic_state", "eye_state", "object_seen_state", "mouth_interactions_state",
                "nose_state", "object_smelt_state", "chosen_action_state", "body_state",
                "behavioural_efficiency_state", "regulation_batch_stat", "engram_state",
                "sleep_episode_state", "consolidation_episode_stat", "consolidation_batch_stat",
                "memory_trace_stat", "expectancy_state", "neuromodulator_state_log", "endocrine_state_log",
        };

        /**
         * DuckDB dialect of config/schema.sql, issued once per JVM at {@link #configure}. Kept
         * inline (not a separate mounted init script) because an embedded database has no
         * separate server process to bootstrap against - see config/schema.sql's own header for
         * why this is (almost) verbatim what used to run against Postgres.
         */
        private static final String[] SCHEMA_DDL = {
                "CREATE SCHEMA IF NOT EXISTS data",
                "CREATE TABLE IF NOT EXISTS data.change_stimulus_state (" +
                        "id uuid NOT NULL PRIMARY KEY, componentclass varchar, time bigint, key bigint, sequential bigint)",
                "CREATE TABLE IF NOT EXISTS data.stimulus_state (" +
                        "id uuid NOT NULL PRIMARY KEY, stimulusclass varchar, type varchar, componentkey bigint, " +
                        "stimulusseq bigint, changestimulusemitted_id uuid, changestimulusreceived_id uuid)",
                "CREATE TABLE IF NOT EXISTS data.creature_state (" +
                        "id uuid NOT NULL PRIMARY KEY, borntime bigint, deadtime bigint, gender boolean, " +
                        "father_key bigint, father_sequential bigint, mother_key bigint, mother_sequential bigint, " +
                        "creature_key bigint, creature_sequential bigint)",
                "CREATE TABLE IF NOT EXISTS data.emotional_state (" +
                        "id uuid NOT NULL PRIMARY KEY, apathy_arausal double, curiosity_arausal double, " +
                        "fear_arausal double, fertility_arausal double, hunger_arausal double, pain_arausal double, " +
                        "sleep_arausal double, stress_arausal double, tedium_arausal double)",
                "CREATE TABLE IF NOT EXISTS data.internal_dynamic_state (" +
                        "id uuid NOT NULL PRIMARY KEY, changestimulusstate_id uuid, finalemotionalstate_id uuid, " +
                        "initialemotionalstate_id uuid)",
                "CREATE TABLE IF NOT EXISTS data.eye_state (" +
                        "id uuid NOT NULL PRIMARY KEY, finalopening double, finalstartangle double, " +
                        "initialopening double, initialstartangle double, changestimulusstate_id uuid)",
                "CREATE TABLE IF NOT EXISTS data.object_seen_state (" +
                        "id uuid NOT NULL PRIMARY KEY, angle double, direction double, distance double, " +
                        "type varchar, key bigint, sequential bigint, changestimulusstate_id uuid)",
                "CREATE TABLE IF NOT EXISTS data.mouth_interactions_state (" +
                        "id uuid NOT NULL PRIMARY KEY, objecttype varchar, type varchar, key bigint, " +
                        "sequential bigint, changestimulusstate_id uuid)",
                "CREATE TABLE IF NOT EXISTS data.nose_state (" +
                        "id uuid NOT NULL PRIMARY KEY, key bigint, sequential bigint)",
                "CREATE TABLE IF NOT EXISTS data.object_smelt_state (" +
                        "id uuid NOT NULL PRIMARY KEY, objecttype varchar, smelltype varchar, key bigint, " +
                        "sequential bigint, changestimulusstate_id uuid)",
                "CREATE TABLE IF NOT EXISTS data.chosen_action_state (" +
                        "id uuid NOT NULL PRIMARY KEY, action varchar, actionselectiontype varchar, " +
                        "inference_duration_ms bigint, key bigint, sequential bigint, changestimulusstate_id uuid)",
                "CREATE TABLE IF NOT EXISTS data.body_state (" +
                        "id uuid NOT NULL PRIMARY KEY, finalx double, finaly double, initialx double, " +
                        "initialy double, speed double, stimulusstate_id uuid)",
                "CREATE TABLE IF NOT EXISTS data.behavioural_efficiency_state (" +
                        "id uuid NOT NULL PRIMARY KEY, behaviouralefficiency double, complextask boolean, " +
                        "numberofobjects integer, changestimulusstate_id uuid)",
                "CREATE TABLE IF NOT EXISTS data.regulation_batch_stat (" +
                        "id uuid NOT NULL PRIMARY KEY, batchsize integer, drivestouchedmask integer, " +
                        "regulatingcount integer, samedrivecollision boolean, changestimulusstate_id uuid)",
                "CREATE TABLE IF NOT EXISTS data.engram_state (" +
                        "id uuid NOT NULL PRIMARY KEY, action_type varchar, creature_key bigint, cycle_gap bigint, " +
                        "eligibility double, emotion_delta double, lay_cycle bigint, reinforced_cycle bigint)",
                "CREATE TABLE IF NOT EXISTS data.sleep_episode_state (" +
                        "id uuid NOT NULL PRIMARY KEY, creature_key bigint, duration_ticks integer, " +
                        "onset_cycle bigint, wake_cycle bigint)",
                "CREATE TABLE IF NOT EXISTS data.consolidation_episode_stat (" +
                        "id uuid NOT NULL PRIMARY KEY, aborted boolean, batches_completed integer, " +
                        "creature_key bigint, engram_count integer, mean_eligibility double, onset_cycle bigint, " +
                        "std_eligibility double)",
                "CREATE TABLE IF NOT EXISTS data.consolidation_batch_stat (" +
                        "id uuid NOT NULL PRIMARY KEY, batch_index integer, batch_size integer, " +
                        "creature_key bigint, loss double, onset_cycle bigint)",
                "CREATE TABLE IF NOT EXISTS data.memory_trace_stat (" +
                        "id uuid NOT NULL PRIMARY KEY, creature_key bigint, engram_count integer, " +
                        "groups_consolidated integer, onset_cycle bigint)",
                "CREATE TABLE IF NOT EXISTS data.expectancy_state (" +
                        "id uuid NOT NULL PRIMARY KEY, action varchar, creature_key bigint, cycle bigint, " +
                        "drive varchar, drive_level double, expected double, mode varchar, reward double, " +
                        "rpe double, target varchar)",
                "CREATE TABLE IF NOT EXISTS data.neuromodulator_state_log (" +
                        "id uuid NOT NULL PRIMARY KEY, circadian_phase double, creature_key bigint, " +
                        "dopamine double, orexin double, seq bigint, serotonin double)",
                "CREATE TABLE IF NOT EXISTS data.endocrine_state_log (" +
                        "id uuid NOT NULL PRIMARY KEY, cortisol_tonic double, creature_key bigint, seq bigint, " +
                        "stress_level double)",
        };
    }
}
