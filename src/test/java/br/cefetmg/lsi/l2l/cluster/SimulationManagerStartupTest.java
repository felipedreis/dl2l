package br.cefetmg.lsi.l2l.cluster;

import akka.actor.ActorSystem;
import akka.actor.Props;
import akka.testkit.javadsl.TestKit;
import br.cefetmg.lsi.l2l.cluster.settings.Simulation;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The manager must not begin the startup sequence until every role it depends on has registered.
 *
 * <p>It used to begin as soon as the last <em>holder</em> registered, from inside that branch of
 * {@code handleRegister}, so a later {@code idProvider} registration could not trigger it.
 * {@code startSimulation()} asks the idProvider for object ids straight away, so whenever the
 * holder won that race the ask went to a null ref, the manager died with
 * {@code "question not sent to [null]"}, and the holders waited forever for creatures that were
 * never coming.
 *
 * <p>The failure is silent and unbounded: {@code maxRuntimeMinutes} schedules
 * {@code MaxRuntimeExpired} to the manager itself, so once the manager is dead the watchdog fires
 * into dead letters and the run hangs until someone kills it. Seen live on 2026-08-10, when the
 * p84 pilot's second trial sat idle 47 minutes past a 45-minute cap while its first trial, on the
 * identical config, had finished in four.
 *
 * <p>Registration order is genuinely nondeterministic across four JVMs, so both orderings are
 * tested. The holder-first case is the one that used to crash.
 */
public class SimulationManagerStartupTest {

    private static ActorSystem system;

    @BeforeAll
    static void boot() {
        system = ActorSystem.create("startup-test", config());
    }

    @AfterAll
    static void shutdown() {
        TestKit.shutdownActorSystem(system);
        system = null;
    }

    @Test
    void holder_registering_before_the_id_provider_does_not_start_or_kill_the_manager() {
        new TestKit(system) {{
            TestKit holder = new TestKit(system);
            TestKit idProvider = new TestKit(system);
            TestKit detector = new TestKit(system);
            var manager = system.actorOf(Props.create(SimulationManager.class, settings()));
            watch(manager);

            // The crashing order: holder first, idProvider last.
            manager.tell(new Register("holder"), holder.getRef());
            holder.expectMsgClass(Duration.ofSeconds(3), Long.class);   // its holder index

            // Nothing may happen yet — startSimulation() would ask a null idProvider.
            holder.expectNoMessage(Duration.ofSeconds(2));
            expectNoMessage(Duration.ofMillis(200));                    // and no Terminated

            manager.tell(new Register("collisionDetector"), detector.getRef());
            holder.expectNoMessage(Duration.ofSeconds(1));

            // Last dependency registers: now, and only now, the handshake begins.
            manager.tell(new Register("idProvider"), idProvider.getRef());

            holder.expectMsgClass(Duration.ofSeconds(5), AckReady.class);
            holder.reply(new Ready(true));
            idProvider.expectMsgClass(Duration.ofSeconds(5), AskForIds.class);
        }};
    }

    @Test
    void id_provider_registering_first_still_starts_once_the_holder_arrives() {
        new TestKit(system) {{
            TestKit holder = new TestKit(system);
            TestKit idProvider = new TestKit(system);
            TestKit detector = new TestKit(system);
            var manager = system.actorOf(Props.create(SimulationManager.class, settings()));

            manager.tell(new Register("idProvider"), idProvider.getRef());
            manager.tell(new Register("collisionDetector"), detector.getRef());
            idProvider.expectNoMessage(Duration.ofSeconds(1));   // no holder yet, nothing to do

            manager.tell(new Register("holder"), holder.getRef());
            holder.expectMsgClass(Duration.ofSeconds(3), Long.class);

            holder.expectMsgClass(Duration.ofSeconds(5), AckReady.class);
            holder.reply(new Ready(true));
            idProvider.expectMsgClass(Duration.ofSeconds(5), AskForIds.class);
        }};
    }

    @Test
    void the_startup_sequence_runs_only_once() {
        new TestKit(system) {{
            TestKit holder = new TestKit(system);
            TestKit idProvider = new TestKit(system);
            TestKit detector = new TestKit(system);
            var manager = system.actorOf(Props.create(SimulationManager.class, settings()));

            manager.tell(new Register("holder"), holder.getRef());
            holder.expectMsgClass(Duration.ofSeconds(3), Long.class);
            manager.tell(new Register("collisionDetector"), detector.getRef());
            manager.tell(new Register("idProvider"), idProvider.getRef());

            holder.expectMsgClass(Duration.ofSeconds(5), AckReady.class);
            holder.reply(new Ready(true));
            idProvider.expectMsgClass(Duration.ofSeconds(5), AskForIds.class);

            // A late re-registration (a role restarting, a duplicated Register) must not
            // re-run the handshake and spawn the world a second time.
            manager.tell(new Register("collisionDetector"), detector.getRef());
            manager.tell(new Register("idProvider"), idProvider.getRef());
            assertTrue(holder.receiveWhile(Duration.ofSeconds(2), Duration.ofSeconds(2), 10,
                            msg -> msg).stream().noneMatch(m -> m instanceof AckReady),
                    "the startup handshake must not run a second time");
        }};
    }

    /**
     * Simulation takes the ROOT config and resolves "simulation.*" itself — passing
     * getConfig("simulation") here looks reasonable and silently yields a settings object with no
     * creatures at all, so startSimulation() completes without ever asking for ids.
     */
    private static Simulation settings() {
        Simulation s = new Simulation(
                ConfigFactory.parseResources("simulations/integration_empty_world.conf").resolve());
        assertFalse(s.getCreatureSettings().isEmpty(),
                "fixture must request creatures, or AskForIds is never sent and the test proves nothing");
        return s;
    }

    private static Config config() {
        return ConfigFactory.parseString(String.join("\n",
                        "akka.remote.netty.tcp.hostname = 127.0.0.1",
                        "akka.remote.netty.tcp.port = 0",
                        "akka.cluster.seed-nodes = []",
                        "akka.cluster.min-nr-of-members = 1",
                        "akka.loglevel = WARNING"))
                .withFallback(ConfigFactory.load());
    }
}
