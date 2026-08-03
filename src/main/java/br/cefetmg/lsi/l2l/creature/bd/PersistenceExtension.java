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
 * The actual write mechanics are a {@link PersistenceBackend} (currently the sole
 * implementation, {@link ParquetBackend} - an earlier embedded-DuckDB backend was tried and
 * removed once Parquet proved the better path, see its own javadoc and
 * docs/plans/parquet-write-path.md), kept behind this interface so a future alternative
 * doesn't need to touch {@link BDActor} or callers. A single {@link BDActor}/backend
 * instance (no more sharding - not yet proven necessary; revisit only if a local diagnostic
 * shows otherwise).
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

        private final ExtendedActorSystem system;

        private volatile ActorRef bdActor;
        private volatile Path rawDumpDir;

        Impl(ExtendedActorSystem system) {
            this.system = system;
        }

        /**
         * Sets up {@code saveDir}/raw and starts the single {@link BDActor}. Idempotent -
         * safe to call more than once (only the first call takes effect), so callers don't
         * need to coordinate who calls it first. Must complete before any creature is
         * spawned, since {@link CreatureActor}/component actors resolve {@link #bdActor()}
         * in their own {@code preStart()}.
         */
        public synchronized void configure(String saveDir) {
            if (bdActor != null) return;
            try {
                Path dir = Path.of(saveDir);
                Files.createDirectories(dir);
                this.rawDumpDir = dir.resolve("raw");
                Files.createDirectories(rawDumpDir);
            } catch (IOException e) {
                throw new RuntimeException("PersistenceExtension failed to set up its raw dump dir", e);
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
         * Constructs the {@link PersistenceBackend}. Called once, from {@link BDActor}'s own
         * constructor (single instance per JVM - see class javadoc).
         */
        PersistenceBackend newBackend() throws Exception {
            return new ParquetBackend(rawDumpDir);
        }

        /**
         * Every table {@link ParquetBackend} writes, in dependency-irrelevant order (no FK
         * constraints - this is an append-only telemetry log, not a relational store).
         */
        static final String[] TABLES = {
                "change_stimulus_state", "stimulus_state", "creature_state", "emotional_state",
                "internal_dynamic_state", "eye_state", "object_seen_state", "mouth_interactions_state",
                "nose_state", "object_smelt_state", "chosen_action_state", "body_state",
                "behavioural_efficiency_state", "regulation_batch_stat", "engram_state",
                "sleep_episode_state", "consolidation_episode_stat", "consolidation_batch_stat",
                "memory_trace_stat", "expectancy_state", "neuromodulator_state_log", "endocrine_state_log",
        };
    }
}
