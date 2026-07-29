package br.cefetmg.lsi.l2l.creature.bd;

import akka.Done;
import akka.actor.AbstractExtensionId;
import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.actor.CoordinatedShutdown;
import akka.actor.ExtendedActorSystem;
import akka.actor.Extension;
import akka.actor.Props;
import akka.pattern.Patterns;

import javax.persistence.EntityManagerFactory;
import javax.persistence.Persistence;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletionStage;

/**
 * Akka Extension holding one JPA {@link EntityManagerFactory} per JVM node, plus the single
 * {@link BDActor} that owns all creature persistence writes for that JVM.
 *
 * CONFIRMED LIVE on CCAD node c1 (2026-07-28/29): every creature component actor
 * independently called {@code Persistence.createEntityManagerFactory("L2LPU", ...)}
 * in its own {@code preStart()} (~70-100 separate factories per holder - one per
 * creature component). A live JVM thread dump caught multiple component-dispatcher
 * threads blocked on the exact same EclipseLink {@code SequencingManager} lock
 * object, one of them doing a synchronous blocking Postgres round-trip to fetch a
 * new entity-ID sequence value - serializing creature cognition behind persistence
 * I/O on the same dispatcher that runs PartialAppraisal/HomeostaticRegulation/etc.
 * Sharing one factory per JVM lets EclipseLink's sequence pre-allocation cache
 * actually do its job (one warm shared cache instead of ~70-100 cold independent
 * ones) and avoids opening ~70-100 separate JDBC connection pools against the same
 * Postgres instance.
 *
 * The shared factory alone doesn't stop every {@code persist()} call from doing a
 * synchronous, blocking transaction inline on {@code component-dispatcher} - see
 * {@code docs/plans/bdactor-async-persistence-with-drain.md}. {@link BDActor} (one
 * per JVM, owned here) batches writes off that dispatcher entirely; a
 * {@link CoordinatedShutdown} task registered below guarantees its mailbox is
 * drained before the ActorSystem terminates, so no in-flight write is lost on
 * shutdown.
 *
 * Usage: PersistenceExtension.of(context().system()).entityManagerFactory().createEntityManager()
 * PersistenceExtension.of(context().system()).bdActor()
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

        private final EntityManagerFactory emf;
        private final ActorRef bdActor;

        Impl(ExtendedActorSystem system) {
            this.emf = Persistence.createEntityManagerFactory("L2LPU", jdbcUrlOverride());
            this.bdActor = system.actorOf(Props.create(BDActor.class).withDispatcher("bd-dispatcher"), "bd");

            // Defense-in-depth for shutdown paths that bypass Holder's own two drain points
            // (handleRemoveObject/handleFinish - see docs/plans/bdactor-async-persistence-with-drain.md
            // §4): a crashed/killed holder JVM, docker-compose down/SIGTERM, or a cluster Down
            // event. PhaseBeforeActorSystemTerminate runs after cluster shutdown and before
            // ActorSystem termination, on every realistic shutdown path (ActorSystem.terminate()
            // and the JVM shutdown hook both trigger CoordinatedShutdown by default in this
            // project's unmodified config).
            CoordinatedShutdown.get(system).addTask(
                    CoordinatedShutdown.PhaseBeforeActorSystemTerminate(), "drain-bdactor",
                    () -> drain(system));
        }

        private CompletionStage<Done> drain(ActorSystem system) {
            // CONFIRMED LIVE: Holder.handleFinish() calls context().system().terminate()
            // directly (not CoordinatedShutdown.run()), which tears down /user's children -
            // including bdActor - without reliably waiting for this task's phase first. In
            // the common case Holder's own two explicit drains (handleRemoveObject/
            // handleFinish, §4a/§4b) already flushed everything before that happened, so by
            // the time this task runs, bdActor may already be stopped - an expected outcome
            // here (nothing left to flush), not a failure. Only a genuine 30s timeout with
            // bdActor still alive (this task's actual defense-in-depth purpose, e.g. a
            // crashed JVM/SIGTERM path that skipped Holder's drains) should surface as one.
            return Patterns.ask(bdActor, new Flush(), Duration.ofSeconds(30))
                    .thenApply(reply -> Done.done())
                    .exceptionally(ex -> {
                        String msg = ex.getMessage();
                        if (msg != null && msg.contains("already been terminated")) {
                            return Done.done();
                        }
                        throw new RuntimeException(ex);
                    });
        }

        public EntityManagerFactory entityManagerFactory() {
            return emf;
        }

        public ActorRef bdActor() {
            return bdActor;
        }

        /**
         * DL2L_DB_URL lets deployments without a fixed "dl2l-db" hostname (e.g. Singularity
         * instances on CCAD, which share the host's network namespace and use localhost/a
         * per-role port instead of Docker's per-container DNS) point at their actual postgres
         * address without touching persistence.xml. Unset -> identical to today's hardcoded URL.
         */
        private static Map<String, Object> jdbcUrlOverride() {
            Map<String, Object> overrides = new HashMap<>();
            String dbUrl = System.getenv("DL2L_DB_URL");
            if (dbUrl != null && !dbUrl.isEmpty()) {
                overrides.put("javax.persistence.jdbc.url", dbUrl);
            }
            return overrides;
        }
    }
}
