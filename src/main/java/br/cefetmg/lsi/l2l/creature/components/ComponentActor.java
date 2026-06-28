package br.cefetmg.lsi.l2l.creature.components;

import akka.actor.TypedActor;
import akka.actor.TypedProps;
import akka.actor.UntypedActor;
import br.cefetmg.lsi.l2l.creature.AkkaComponentRef;
import br.cefetmg.lsi.l2l.creature.Creature;
import br.cefetmg.lsi.l2l.creature.CreatureActor;
import br.cefetmg.lsi.l2l.creature.bd.JpaPersister;
import br.cefetmg.lsi.l2l.creature.bd.Persister;

/**
 * Akka adapter that owns a {@link CreatureComponent} and forwards mailbox messages
 * to it. Created via {@code Props.create(ComponentActor.class, () -> new ComponentActor(new Eye(id)))}
 * by {@code CreatureActor.init()}.
 *
 * In {@link #preStart()} the adapter resolves the parent {@link Creature} via the
 * existing {@code TypedActor} lookup and wires it into the component along with a
 * {@link Persister} (JPA-backed by default) and a self-{@link AkkaComponentRef}.
 */
public class ComponentActor extends UntypedActor {

    private final CreatureComponent component;
    private final java.util.function.Supplier<Persister> persisterFactory;
    private Persister persister;

    public ComponentActor(CreatureComponent component) {
        this(component, JpaPersister::new);
    }

    public ComponentActor(CreatureComponent component, java.util.function.Supplier<Persister> persisterFactory) {
        this.component = component;
        this.persisterFactory = persisterFactory;
    }

    @Override
    public void preStart() throws Exception {
        super.preStart();
        persister = persisterFactory.get();
        Creature creature = TypedActor.get(context().system())
                .typedActorOf(new TypedProps<>(Creature.class, CreatureActor.class), context().parent());
        component.init(creature, persister, new AkkaComponentRef(self()));
    }

    @Override
    public void onReceive(Object message) {
        component.onReceive(message);
    }

    @Override
    public void postStop() throws Exception {
        try {
            component.postStop();
        } finally {
            if (persister != null) persister.close();
            super.postStop();
        }
    }
}
