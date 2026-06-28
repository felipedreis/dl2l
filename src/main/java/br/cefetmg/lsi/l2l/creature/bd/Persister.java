package br.cefetmg.lsi.l2l.creature.bd;

/**
 * Persistence boundary for creature components. The production implementation writes
 * states through JPA; the test implementation drops them. Decoupling persistence from
 * the component lifecycle is what lets {@code TestingCreature} construct components
 * without bootstrapping an EntityManagerFactory.
 */
public interface Persister {

    void persist(PersistenceState... states);

    void close();
}
