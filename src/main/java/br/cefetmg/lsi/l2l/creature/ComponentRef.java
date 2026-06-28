package br.cefetmg.lsi.l2l.creature;

/**
 * A one-method sink that abstracts the destination of a message inside (or just outside)
 * the creature pipeline. Production wraps a real Akka {@code ActorRef}; tests wrap a
 * synchronous in-process dispatcher. Components depend on this interface rather than
 * {@code ActorRef} so they can run without an {@code ActorSystem}.
 */
public interface ComponentRef {
    void tell(Object msg);
}
