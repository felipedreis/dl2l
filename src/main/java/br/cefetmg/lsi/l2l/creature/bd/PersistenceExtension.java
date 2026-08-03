package br.cefetmg.lsi.l2l.creature.bd;

import akka.actor.AbstractExtensionId;
import akka.actor.ActorRef;
import akka.actor.ActorRefWithCell;
import akka.actor.ActorSystem;
import akka.actor.CoordinatedShutdown;
import akka.actor.ExtendedActorSystem;
import akka.actor.Extension;
import akka.actor.Props;
import akka.actor.Terminated;
import akka.actor.UntypedActor;
import akka.pattern.Patterns;
import br.cefetmg.lsi.l2l.metrics.MetricsExtension;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

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
 * docs/plans/parquet-write-path.md. A direct-Parquet backend fixed the local OOM but CCAD
 * still OOM'd at scale (mailbox backlog + on-heap Parquet row-group buffers) - see
 * docs/plans/arrow-ipc-write-path.md for the write path now in place: off-heap Arrow IPC
 * buffers plus a fast-fail watchdog instead of any adaptive backpressure into the simulation.
 *
 * <p>The actual write mechanics are a {@link PersistenceBackend} (currently the sole
 * implementation, {@link ArrowIpcBackend} - two earlier backends, embedded-DuckDB and
 * direct-Parquet, were tried and removed as each proved the wrong tool), kept behind this
 * interface so a future alternative doesn't need to touch {@link BDActor} or callers. A single
 * {@link BDActor}/backend instance (no more sharding - not yet proven necessary; revisit only
 * if a local diagnostic shows otherwise).
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

        private static final Logger logger = Logger.getLogger(Impl.class.getName());

        private final ExtendedActorSystem system;

        private volatile ActorRef bdActor;
        private volatile Path rawDumpDir;

        /**
         * Flips to {@code true} once the {@code drain-bdactor} {@link CoordinatedShutdown} task
         * (below) has started tearing BDActor down deliberately. The death-watch actor (see
         * {@link BdActorDeathWatch}) treats any {@link Terminated} observed BEFORE this flips as
         * an unexpected crash - see docs/plans/arrow-ipc-write-path.md, W3's "no
         * silent-dead-writer" requirement.
         */
        private final AtomicBoolean orderlyShutdown = new AtomicBoolean(false);

        private volatile Thread watchdogThread;

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
                            .thenApply(v -> {
                                orderlyShutdown.set(true);
                                return akka.Done.done();
                            })
                            .exceptionally(ex -> {
                                orderlyShutdown.set(true);
                                String msg = ex.getMessage();
                                if (msg != null && msg.contains("already been terminated")) {
                                    return akka.Done.done();
                                }
                                throw new RuntimeException(ex);
                            }));

            system.actorOf(Props.create(BdActorDeathWatch.class,
                    () -> new BdActorDeathWatch(bdActor, orderlyShutdown, rawDumpDir, MetricsExtension.of(system))),
                    "bd-death-watch");

            startWatchdog();
        }

        /**
         * Daemon {@link Thread} (not an Akka-scheduled task - immune to dispatcher starvation
         * and keeps sampling under GC pressure) polling BDActor's own mailbox depth every 5s.
         * See {@link PersistenceWatchdog} for the trip/hysteresis logic and
         * docs/plans/arrow-ipc-write-path.md, W3.
         */
        private void startWatchdog() {
            long thresholdEnvValue = PersistenceWatchdog.thresholdFromEnv();
            MetricsExtension.Impl metricsExt = MetricsExtension.of(system);
            PersistenceWatchdog watchdog = new PersistenceWatchdog(
                    () -> ((ActorRefWithCell) bdActor).underlying().numberOfMessages(),
                    thresholdEnvValue,
                    () -> FatalPersistenceHalt.trip(
                            "DL2L-FATAL-PERSISTENCE-OVERLOAD",
                            "queueDepth>=threshold=" + thresholdEnvValue
                                    + " heapUsedBytes=" + (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory())
                                    + " heapMaxBytes=" + Runtime.getRuntime().maxMemory(),
                            rawDumpDir, "PERSISTENCE_OVERLOAD", metricsExt, 3));

            watchdogThread = new Thread(() -> {
                while (!Thread.currentThread().isInterrupted() && !watchdog.tripped()) {
                    try {
                        Thread.sleep(5000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    watchdog.sample();
                }
            }, "dl2l-persistence-watchdog");
            watchdogThread.setDaemon(true);
            watchdogThread.start();
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
            return new ArrowIpcBackend(rawDumpDir, MetricsExtension.of(system));
        }
    }

    /**
     * "No silent-dead-writer" detector (docs/plans/arrow-ipc-write-path.md, W3) - the historical
     * failure mode this guards against is BDActor dying (an uncaught exception -
     * {@code StoppingSupervisorStrategy} stops rather than restarts it) and every subsequent
     * {@code persist()} silently going to deadLetters, producing valid-but-empty raw output with
     * no visible error short of grepping the log. {@link akka.actor.ActorContext#watch} delivers
     * exactly one {@link Terminated} the moment that happens; {@link Impl#orderlyShutdown} is the
     * only thing distinguishing that from the one expected {@link Terminated} at final JVM
     * shutdown.
     */
    static final class BdActorDeathWatch extends UntypedActor {

        private final AtomicBoolean orderlyShutdown;
        private final Path rawDumpDir;
        private final MetricsExtension.Impl metricsExt;

        BdActorDeathWatch(ActorRef bdActor, AtomicBoolean orderlyShutdown, Path rawDumpDir,
                           MetricsExtension.Impl metricsExt) {
            this.orderlyShutdown = orderlyShutdown;
            this.rawDumpDir = rawDumpDir;
            this.metricsExt = metricsExt;
            getContext().watch(bdActor);
        }

        @Override
        public void onReceive(Object message) {
            if (message instanceof Terminated) {
                if (!orderlyShutdown.get()) {
                    FatalPersistenceHalt.trip(
                            "DL2L-FATAL-PERSISTENCE-DEAD",
                            "BDActor terminated outside the orderly CoordinatedShutdown drain path",
                            rawDumpDir, "PERSISTENCE_DEAD", metricsExt, 4);
                }
            } else {
                unhandled(message);
            }
        }
    }
}
