package br.cefetmg.lsi.l2l.creature.bd;

import akka.actor.ActorRefWithCell;
import akka.actor.PoisonPill;
import akka.actor.UntypedActor;
import br.cefetmg.lsi.l2l.metrics.MetricsExtension;

import javax.persistence.EntityManager;
import java.util.List;
import java.util.logging.Logger;

/**
 * One per JVM (owned by {@link PersistenceExtension}), on its own {@code bd-dispatcher}
 * (a {@code PinnedDispatcher} - single dedicated OS thread, so the {@link EntityManager}
 * below is only ever touched by one thread despite not being thread-safe itself). Every
 * creature component's {@code persist()} call routes here via {@code creature.bd().tell(state)}
 * instead of blocking {@code component-dispatcher} with a synchronous transaction - see
 * docs/plans/bdactor-async-persistence-with-drain.md.
 *
 * Relies on {@link br.cefetmg.lsi.l2l.common.ComponentMessageQueue} batching every
 * {@link PersistenceState} queued between polls into one {@code List}, delivered as a single
 * {@code onReceive} call - so one transaction commits an entire backlog at once, and the
 * busier this actor is, the larger (and more Postgres-round-trip-efficient) each batch
 * becomes, rather than falling further behind.
 */
public class BDActor extends UntypedActor {

    private final EntityManager em;

    private final MetricsExtension.Impl metricsExt;

    private Logger logger = Logger.getLogger(BDActor.class.getSimpleName());

    public BDActor() {
        this.em = PersistenceExtension.of(getContext().system())
                .entityManagerFactory().createEntityManager();
        this.metricsExt = MetricsExtension.of(getContext().system());
    }

    @Override
    public void onReceive(Object message) throws Exception {
        if (message instanceof List) {
            List batch = (List) message;
            if (batch.size() == 1 && batch.get(0) instanceof Flush) {
                // Mailbox FIFO ordering guarantees every PersistenceState queued strictly
                // before this Flush was already delivered and committed in an earlier
                // onReceive call (ComponentMessageQueue never merges an unrecognized message
                // type, e.g. Flush, into an in-progress Stimulus/PersistenceState batch), so
                // there is nothing left to do here except ack.
                sender().tell(new FlushAck(), self());
                return;
            }

            logger.fine("Persisting " + batch.size() + " states");
            metricsExt.setGauge("dl2l_bdactor_batch_size", batch.size());

            // Remaining backlog still queued after this dequeue() - distinct from batch size
            // above (what THIS transaction is about to commit). Sustained, non-decreasing
            // growth here is the signal docs/plans/bdactor-async-persistence-with-drain.md §5
            // named as the trigger to revisit backpressure - see
            // docs/plans/issue-77-bdactor-oom-fix.md.
            metricsExt.setGauge("dl2l_bdactor_queue_depth",
                    ((ActorRefWithCell) getSelf()).underlying().numberOfMessages());

            long startNanos = System.nanoTime();
            em.getTransaction().begin();
            for (Object state : batch) {
                if (state instanceof PersistenceState)
                    em.persist(state);
            }
            em.getTransaction().commit();
            em.clear();
            metricsExt.setGauge("dl2l_bdactor_persist_duration_seconds",
                    (System.nanoTime() - startNanos) / 1_000_000_000.0);

        } else if (message instanceof PoisonPill) {
            getContext().stop(self());
            logger.info("BDActor gonna stop");
        } else
            unhandled(message);
    }

    @Override
    public void postStop() throws Exception {
        super.postStop();

        // Last-resort safety net, should be unreachable in the normal shutdown path now that
        // callers drain via Flush (see Holder.handleRemoveObject/handleFinish and
        // PersistenceExtension's CoordinatedShutdown task) before this actor is ever stopped.
        if(em.getTransaction().isActive())
            em.getTransaction().commit();
        em.close();
    }
}
