package br.cefetmg.lsi.l2l.creature.bd;

import akka.actor.AbstractExtensionId;
import akka.actor.ActorSystem;
import akka.actor.ExtendedActorSystem;
import akka.actor.Extension;

import javax.persistence.EntityManagerFactory;
import javax.persistence.Persistence;

/**
 * Akka Extension holding one JPA {@link EntityManagerFactory} per JVM node.
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
 * Usage: PersistenceExtension.of(context().system()).entityManagerFactory().createEntityManager()
 * (each caller still gets its own EntityManager - EntityManagerFactory is the
 * thread-safe, shareable part; EntityManager is not meant to be shared across threads).
 */
public class PersistenceExtension extends AbstractExtensionId<PersistenceExtension.Impl> {

    public static final PersistenceExtension Id = new PersistenceExtension();

    public static Impl of(ActorSystem system) {
        return system.registerExtension(Id);
    }

    @Override
    public Impl createExtension(ExtendedActorSystem system) {
        return new Impl();
    }

    public static class Impl implements Extension {

        private final EntityManagerFactory emf;

        Impl() {
            this.emf = Persistence.createEntityManagerFactory("L2LPU", JpaPersister.jdbcUrlOverride());
        }

        public EntityManagerFactory entityManagerFactory() {
            return emf;
        }
    }
}
