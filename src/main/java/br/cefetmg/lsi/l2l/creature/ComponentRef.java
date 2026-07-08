package br.cefetmg.lsi.l2l.creature;

/**
 * A one-method sink that abstracts the destination of a message inside (or just outside)
 * the creature pipeline. Production wraps a real Akka {@code ActorRef}; tests wrap a
 * synchronous in-process dispatcher. Components depend on this interface rather than
 * {@code ActorRef} so they can run without an {@code ActorSystem}.
 */
public interface ComponentRef {
    void tell(Object msg);

    /**
     * Send {@code msg} with a reply-to {@code sender}, so a request that expects a response (e.g. a
     * {@code DestructiveStimulus} whose target replies with an {@code EnergeticStimulus}) can route
     * the reply back to {@code sender}. Defaults to a sender-less {@link #tell(Object)} for sinks that
     * don't model Akka sender semantics (e.g. test stubs).
     */
    default void tell(Object msg, ComponentRef sender) {
        tell(msg);
    }
}
