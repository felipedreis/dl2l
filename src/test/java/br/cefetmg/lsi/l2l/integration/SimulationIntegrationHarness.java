package br.cefetmg.lsi.l2l.integration;

import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.actor.Props;
import akka.cluster.Cluster;
import akka.pattern.Patterns;
import akka.stream.Materializer;
import br.cefetmg.lsi.l2l.cluster.CollisionDetectorActor;
import br.cefetmg.lsi.l2l.cluster.Holder;
import br.cefetmg.lsi.l2l.cluster.SequentialIdProvider;
import br.cefetmg.lsi.l2l.cluster.SimulationManager;
import br.cefetmg.lsi.l2l.cluster.settings.Simulation;
import br.cefetmg.lsi.l2l.creature.bd.ArrowTestReader;
import br.cefetmg.lsi.l2l.creature.bd.DumpParquet;
import br.cefetmg.lsi.l2l.creature.bd.PersistenceExtension;
import br.cefetmg.lsi.l2l.metrics.MetricsExtension;
import br.cefetmg.lsi.l2l.web.GeometrySourceProvider;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.search.Search;
import scala.concurrent.Await;
import scala.concurrent.duration.Duration;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * Boots a whole DL2L simulation - all four cluster roles, one real {@link ActorSystem}, one
 * JVM - so tests can assert on what the system actually does rather than on what a
 * single-threaded stand-in does.
 *
 * <p>This exists because {@code TestingCreature} cannot express the properties issue #85 is
 * about. It has no scheduler (its {@code tick()} is the test calling a method), its
 * {@code BatchingDispatcher} is an analogue of {@code ComponentMessageQueue} rather than the
 * real thing, and it has no collision detector at all - so the cognitive-cycle rate, the
 * interaction between the pacemaker and the detector's asynchronous replies, and the
 * perception flicker that interaction produced are all invisible to it.
 *
 * <p>Cluster setup differs from {@code Main} in exactly three ways, all forced by running in
 * a test JVM: remoting binds an ephemeral port (so parallel test classes and a developer's
 * running simulation cannot collide), the node joins itself instead of a configured seed
 * (there is no fixed address to configure when the port is ephemeral), and
 * {@code min-nr-of-members} drops to 1. The {@code GeometryWebService} is not started - it
 * would bind port 8080 for a UI nobody is watching.
 *
 * <p>Runs are bounded windows, not run-to-death: a creature is calibrated to live ~150 s, far
 * too long for CI. Boot one harness per test class, not per test.
 */
public final class SimulationIntegrationHarness implements AutoCloseable {

    private final ActorSystem system;
    private final Path saveDir;
    /** Set by the first {@link #finalizeAndRead} - the dump can only be finalized once. */
    private boolean finalized = false;

    private SimulationIntegrationHarness(ActorSystem system, Path saveDir) {
        this.system = system;
        this.saveDir = saveDir;
    }

    /**
     * Boots the simulation described by {@code simulationResource} (a HOCON file on the test
     * classpath) and blocks until the first creature is alive and cognizing.
     *
     * @param overrides extra HOCON applied on top of the simulation file - used to flip
     *                  {@code learningSettings.tickGatedCognition} between arms without a
     *                  second config file. Pass {@code ""} for none.
     */
    public static SimulationIntegrationHarness boot(String simulationResource, String overrides, Path saveDir) {
        Config simulationConfig = ConfigFactory.parseString(overrides)
                .withFallback(ConfigFactory.parseResources(simulationResource))
                .resolve();
        Simulation simulation = new Simulation(simulationConfig);

        ActorSystem system = ActorSystem.create("l2l", clusterConfig());
        MetricsExtension.of(system);

        Materializer materializer = Materializer.matFromSystem(system);
        // Manager first. Every other role registers with it by resolving /user/manager when
        // it sees MemberUp, so if it does not exist yet that lookup fails with
        // ActorNotFound and the role never registers - the manager then blocks forever
        // waiting for a holder, or asks a null idProvider for ids. Across separate JVMs
        // this is handled by starting the manager node first (see scripts/manager.sh);
        // in one JVM it is just creation order.
        system.actorOf(Props.create(SimulationManager.class, simulation)
                .withDispatcher("cluster-dispatcher"), "manager");
        system.actorOf(Props.create(SequentialIdProvider.class), "idProvider");
        system.actorOf(Props.create(CollisionDetectorActor.class, simulation,
                        new GeometrySourceProvider(materializer))
                .withDispatcher("collision-dispatcher"), "collisionDetector");

        // Self-join before the holder exists: with an ephemeral remoting port there is no
        // address to configure in seed-nodes ahead of time, so the node forms its own
        // single-member cluster here.
        Cluster cluster = Cluster.get(system);
        cluster.join(cluster.selfAddress());

        // The holder is created last, and after a settle, so this harness boots in the same
        // order deployment does. It is no longer load-bearing: the manager used to start as
        // soon as the expected holder count was reached (after a fixed 5 s sleep) and then ask
        // whatever was in its idProvider field for ids, so losing that race killed the run with
        // "question not sent to [null]". SimulationManager.maybeStartSimulation now waits for
        // every role it depends on, and SimulationManagerStartupTest pins both orderings. The
        // settle stays because SequentialIdProvider.handleNewMember still blocks its own
        // dispatcher thread on a 5 s Await while resolving /user/manager, which is a separate
        // fragility this harness cannot fix.
        sleep(1000);
        system.actorOf(Props.create(Holder.class, simulation, saveDir.toString())
                .withDispatcher("cluster-dispatcher"), "holder");

        SimulationIntegrationHarness harness = new SimulationIntegrationHarness(system, saveDir);
        // The manager's startup handshake (holder registration -> AckReady -> world-object
        // distribution -> creature spawn) is asynchronous and involves cluster membership
        // reaching Up, so there is no deterministic point to synchronise on.
        harness.awaitCond(() -> harness.totalCognitiveCycles() > 0,
                java.time.Duration.ofSeconds(90),
                "no creature started cognizing - the cluster handshake did not complete");
        harness.awaitPacemaker();
        return harness;
    }

    /**
     * Blocks until the creature's own clock is demonstrably driving cycles.
     *
     * <p>The first cognitive cycle is not that signal. {@code CreatureActor.init()} sends one
     * positioning broadcast immediately, and the replies to it produce a burst of cycles
     * straight away - but the {@code clock} scheduler only starts firing five seconds later.
     * Between the two there is a dead zone with no cycles at all, and a rate measured inside
     * it reads 0 Hz no matter how the simulation is configured. Waiting for growth across two
     * consecutive samples clears the dead zone without hard-coding the scheduler's delay.
     */
    private void awaitPacemaker() {
        awaitCond(() -> {
            double first = totalCognitiveCycles();
            sleep(500);
            double second = totalCognitiveCycles();
            sleep(500);
            return second > first && totalCognitiveCycles() > second;
        }, java.time.Duration.ofSeconds(60),
                "the creature's pacemaker never started driving cycles");
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted", e);
        }
    }

    /**
     * Local-only, ephemeral-port cluster config. Everything else (dispatchers, mailboxes,
     * frame sizes) comes from the production {@code application.conf} by fallback, so the
     * tests exercise the real dispatcher and mailbox configuration.
     */
    private static Config clusterConfig() {
        return ConfigFactory.parseString(String.join("\n",
                        "akka.remote.netty.tcp.hostname = 127.0.0.1",
                        "akka.remote.netty.tcp.port = 0",
                        "akka.cluster.seed-nodes = []",
                        "akka.cluster.min-nr-of-members = 1",
                        "akka.cluster.roles = [manager, idProvider, collisionDetector, holder]",
                        // Tests assert on rates and counts; Akka's INFO cluster chatter buries them.
                        "akka.loglevel = WARNING"))
                .withFallback(ConfigFactory.load());
    }

    public ActorSystem system() {
        return system;
    }

    /** Stops the named top-level actor, e.g. to simulate the collision detector going away. */
    public void stopActor(String name) throws Exception {
        ActorRef ref = Await.result(
                system.actorSelection("/user/" + name).resolveOne(
                        akka.util.Timeout.apply(10, TimeUnit.SECONDS)),
                Duration.apply(10, TimeUnit.SECONDS));
        system.stop(ref);
    }

    // --- metrics ------------------------------------------------------------------------

    /**
     * Total {@code dl2l_creature_cognitive_cycles_total} across every creature. This is
     * PartialAppraisal's own counter, incremented once per cognitive cycle (not per message
     * delivery), so it is the direct ground truth for the cycle rate.
     */
    public double totalCognitiveCycles() {
        Search search = MetricsExtension.of(system).registry()
                .find("dl2l_creature_cognitive_cycles_total");
        double total = 0;
        for (Counter c : search.counters()) total += c.count();
        return total;
    }

    /** Cognitive cycles per second, measured over {@code window} of wall-clock time. */
    public double measureCycleRateHz(java.time.Duration window) throws InterruptedException {
        double before = totalCognitiveCycles();
        long startNanos = System.nanoTime();
        Thread.sleep(window.toMillis());
        double elapsedSeconds = (System.nanoTime() - startNanos) / 1e9;
        return (totalCognitiveCycles() - before) / elapsedSeconds;
    }

    // --- persisted data -----------------------------------------------------------------

    /**
     * Finalizes the raw Arrow dump (once) and reads a table back, keyed by column name.
     *
     * <p>A {@link Flush} alone is not enough to read data mid-run, and deliberately so:
     * {@code ArrowIpcBackend} accumulates rows and writes a record batch only on reaching
     * {@code batchRows} or on finalize, and {@code CreatureComponent.persist()} buffers on
     * the producer side ahead of that. Both could be turned off by environment variable, but
     * doing so writes one record batch per row and one mailbox envelope per persist call,
     * which at the pre-fix cycle rate saturates the JVM on I/O and distorts the very rate
     * these tests measure - observed dropping a ~270 Hz simulation to ~1 Hz. So the buffering
     * stays at its production settings and the dump is finalized instead.
     *
     * <p>Finalizing is terminal - the backend drops writes afterwards - so a harness that has
     * been read from must not be expected to keep persisting. Subsequent calls re-read the
     * already-finalized files rather than finalizing again. Keep file-reading tests and
     * rate-measuring tests in separate classes with separate harnesses.
     *
     * <p>Reading the real {@code .arrow} file is deliberate: it is the same artifact
     * {@code dl2l_data.extract} consumes, so a test and the downstream analysis cannot end up
     * disagreeing about what a column means.
     */
    public Map<String, List<Object>> finalizeAndRead(String table) throws Exception {
        if (!finalized) {
            ActorRef bdActor = PersistenceExtension.of(system).bdActor();
            Await.result(Patterns.ask(bdActor, new DumpParquet(), 120_000L),
                    Duration.apply(120, TimeUnit.SECONDS));
            finalized = true;
        }
        // "raw" mirrors PersistenceExtension.configure()'s layout - the same subdirectory
        // dl2l_data.extract's --raw-dir points at.
        return ArrowTestReader.readArrowFile(saveDir.resolve("raw").resolve(table + ".arrow"));
    }

    // --- waiting ------------------------------------------------------------------------

    /**
     * Polls {@code condition} until true or {@code deadline} passes. Used instead of
     * sleep-and-assert so a slow CI runner makes a test slow rather than red.
     */
    public void awaitCond(BooleanSupplier condition, java.time.Duration deadline, String message) {
        long end = System.nanoTime() + deadline.toNanos();
        while (System.nanoTime() < end) {
            if (condition.getAsBoolean()) return;
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted while waiting: " + message, e);
            }
        }
        throw new AssertionError("timed out after " + deadline + ": " + message);
    }

    @Override
    public void close() {
        akka.testkit.javadsl.TestKit.shutdownActorSystem(system,
                Duration.apply(30, TimeUnit.SECONDS), true);
    }
}
