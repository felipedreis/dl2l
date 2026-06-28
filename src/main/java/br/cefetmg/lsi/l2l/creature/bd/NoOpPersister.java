package br.cefetmg.lsi.l2l.creature.bd;

/**
 * {@link Persister} that drops every call. Used by {@code TestingCreature} so tests
 * don't need a real EntityManagerFactory / database.
 */
public final class NoOpPersister implements Persister {

    @Override
    public void persist(PersistenceState... states) {
        // intentionally empty
    }

    @Override
    public void close() {
        // intentionally empty
    }
}
