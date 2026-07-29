package br.cefetmg.lsi.l2l.creature.components;

import akka.actor.TypedActor;
import akka.actor.TypedProps;
import akka.actor.UntypedActor;
import br.cefetmg.lsi.l2l.creature.AkkaComponentRef;
import br.cefetmg.lsi.l2l.creature.Creature;
import br.cefetmg.lsi.l2l.creature.CreatureActor;
import br.cefetmg.lsi.l2l.creature.bd.NoOpPersister;
import br.cefetmg.lsi.l2l.creature.bd.Persister;
import br.cefetmg.lsi.l2l.metrics.MetricsExtension;

/**
 * Akka adapter that owns a {@link CreatureComponent} and forwards mailbox messages
 * to it. Created via {@code Props.create(ComponentActor.class, () -> new ComponentActor(new Eye(id)))}
 * by {@code CreatureActor.init()}.
 *
 * In {@link #preStart()} the adapter resolves the parent {@link Creature} via the
 * existing {@code TypedActor} lookup and wires it into the component along with a
 * self-{@link AkkaComponentRef}. Persistence no longer flows through the injected
 * {@link Persister} in production - {@code CreatureComponent.persist()} tells
 * {@code creature.bd()} directly (see docs/plans/bdactor-async-persistence-with-drain.md) -
 * so a single shared, stateless {@link NoOpPersister} is passed here instead of each
 * component building its own {@code JpaPersister}/{@code EntityManager}. The {@code init()}
 * signature and {@link Persister} parameter are kept as-is rather than narrowed, so a
 * component could still be wired with a real {@code Persister} if ever needed.
 */
public class ComponentActor extends UntypedActor {

    private static final Persister NO_OP_PERSISTER = new NoOpPersister();

    private final CreatureComponent component;

    public ComponentActor(CreatureComponent component) {
        this.component = component;
    }

    @Override
    public void preStart() throws Exception {
        super.preStart();
        Creature creature = TypedActor.get(context().system())
                .typedActorOf(new TypedProps<>(Creature.class, CreatureActor.class), context().parent());
        MetricsExtension.Impl metricsExt = MetricsExtension.of(context().system());
        component.init(creature, NO_OP_PERSISTER, new AkkaComponentRef(self()), metricsExt);
    }

    @Override
    public void onReceive(Object message) {
        component.onReceive(message);
    }

    @Override
    public void postStop() throws Exception {
        component.postStop();
        super.postStop();
    }
}
