package br.cefetmg.lsi.l2l.creature.components;

import akka.actor.TypedActor;
import akka.actor.TypedProps;
import akka.actor.UntypedActor;
import br.cefetmg.lsi.l2l.creature.AkkaComponentRef;
import br.cefetmg.lsi.l2l.creature.Creature;
import br.cefetmg.lsi.l2l.creature.CreatureActor;
import br.cefetmg.lsi.l2l.metrics.MetricsExtension;

/**
 * Akka adapter that owns a {@link CreatureComponent} and forwards mailbox messages
 * to it. Created via {@code Props.create(ComponentActor.class, () -> new ComponentActor(new Eye(id)))}
 * by {@code CreatureActor.init()}.
 *
 * In {@link #preStart()} the adapter resolves the parent {@link Creature} via the
 * existing {@code TypedActor} lookup and wires it into the component along with a
 * self-{@link AkkaComponentRef}. Persistence flows through {@code creature.bd()} directly
 * ({@code CreatureComponent.persist()} - see docs/plans/bdactor-async-persistence-with-drain.md),
 * not through a per-component injected persister.
 */
public class ComponentActor extends UntypedActor {

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
        component.init(creature, new AkkaComponentRef(self()), metricsExt);
    }

    @Override
    public void onReceive(Object message) {
        component.onReceive(message);
    }

    @Override
    public void postStop() throws Exception {
        // Flush any producer-side-buffered persist() states (see CreatureComponent.persist(),
        // docs/plans/arrow-ipc-write-path.md W4) before the subclass's own teardown hook, so a
        // component dying with a sub-threshold batch still pending doesn't drop it.
        component.flushPersistBuffer();
        component.postStop();
        super.postStop();
    }
}
