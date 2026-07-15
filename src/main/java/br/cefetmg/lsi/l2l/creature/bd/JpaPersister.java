package br.cefetmg.lsi.l2l.creature.bd;

import javax.persistence.EntityManager;
import javax.persistence.Persistence;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

/**
 * {@link Persister} backed by JPA / EclipseLink. Behaviour mirrors the inline persist
 * block that used to live in {@code CreatureComponent.persist(PersistenceState...)}.
 */
public final class JpaPersister implements Persister {

    private static final Logger LOGGER = Logger.getLogger(JpaPersister.class.getName());

    private final EntityManager em;

    public JpaPersister() {
        this(Persistence.createEntityManagerFactory("L2LPU", jdbcUrlOverride()).createEntityManager());
    }

    /**
     * DL2L_DB_URL lets deployments without a fixed "dl2l-db" hostname (e.g. Singularity
     * instances on CCAD, which share the host's network namespace and use localhost/a
     * per-role port instead of Docker's per-container DNS) point at their actual postgres
     * address without touching persistence.xml. Unset -> identical to today's hardcoded URL.
     *
     * CONFIRMED LIVE: every "L2LPU" createEntityManagerFactory call site must use this —
     * Holder, CreatureActor, BDActor, MemoryConsolidator and MemoryTraceConsolidator each
     * had their own uncovered call that silently ignored DL2L_DB_URL and always hit the
     * hardcoded dl2l-db hostname, which crashed creature persistence outright on CCAD
     * (no dl2l-db DNS entry there).
     */
    public static Map<String, Object> jdbcUrlOverride() {
        Map<String, Object> overrides = new HashMap<>();
        String dbUrl = System.getenv("DL2L_DB_URL");
        if (dbUrl != null && !dbUrl.isEmpty()) {
            overrides.put("javax.persistence.jdbc.url", dbUrl);
        }
        return overrides;
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
