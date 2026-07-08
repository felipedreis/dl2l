package br.cefetmg.lsi.l2l.creature;

import akka.actor.ActorRef;

/**
 * {@link ComponentRef} backed by a real Akka {@link ActorRef}. Used by {@code CreatureActor}
 * so the {@link Creature} interface can return {@code ComponentRef} while still routing
 * messages through Akka in production.
 */
public final class AkkaComponentRef implements ComponentRef {

    private final ActorRef ref;
    private final ActorRef sender;

    public AkkaComponentRef(ActorRef ref) {
        this(ref, ActorRef.noSender());
    }

    public AkkaComponentRef(ActorRef ref, ActorRef sender) {
        this.ref = ref;
        this.sender = sender;
    }

    public ActorRef actorRef() {
        return ref;
    }

    @Override
    public void tell(Object msg) {
        ref.tell(msg, sender);
    }

    @Override
    public void tell(Object msg, ComponentRef replyTo) {
        // Route the Akka reply-to sender so responses (e.g. EnergeticStimulus from an eaten Fruit)
        // reach the requesting component instead of dead letters.
        ActorRef sender = (replyTo instanceof AkkaComponentRef)
                ? ((AkkaComponentRef) replyTo).actorRef()
                : this.sender;
        ref.tell(msg, sender);
    }
}
