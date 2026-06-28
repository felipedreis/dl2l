package br.cefetmg.lsi.l2l.creature.bd;

import javax.persistence.EntityManager;
import javax.persistence.Persistence;
import java.util.logging.Logger;

/**
 * {@link Persister} backed by JPA / EclipseLink. Behaviour mirrors the inline persist
 * block that used to live in {@code CreatureComponent.persist(PersistenceState...)}.
 */
public final class JpaPersister implements Persister {

    private static final Logger LOGGER = Logger.getLogger(JpaPersister.class.getName());

    private final EntityManager em;

    public JpaPersister() {
        this(Persistence.createEntityManagerFactory("L2LPU").createEntityManager());
    }

    public JpaPersister(EntityManager em) {
        this.em = em;
    }

    @Override
    public void persist(PersistenceState... states) {
        em.getTransaction().begin();
        for (PersistenceState state : states) {
            em.persist(state);
        }
        em.getTransaction().commit();
        em.clear();
    }

    @Override
    public void close() {
        try {
            em.close();
        } catch (Exception e) {
            LOGGER.warning("JpaPersister.close failed: " + e.getMessage());
        }
    }
}
