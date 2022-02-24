package br.cefetmg.lsi.l2l.creature.components;

import akka.actor.ActorRef;
import akka.actor.TypedActor;
import akka.actor.TypedProps;
import akka.actor.UntypedActor;
import br.cefetmg.lsi.l2l.common.SequentialId;
import br.cefetmg.lsi.l2l.creature.Creature;
import br.cefetmg.lsi.l2l.creature.CreatureActor;
import br.cefetmg.lsi.l2l.creature.bd.BodyState;
import br.cefetmg.lsi.l2l.creature.bd.PersistenceState;

import javax.persistence.EntityManager;
import javax.persistence.Persistence;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Logger;

/**
 * Created by felipe on 02/01/17.
 */
public abstract class CreatureComponent extends UntypedActor {

    private static final int PERSISTENCE_BUFFER_LIMIT = 100000;

    protected SequentialId id;

    protected Creature creature;

    protected final Logger logger;

    private SequentialId nextStimulusId;

    private ActorRef bd;

    private final EntityManager em;

    private final List<PersistenceState> persistenceBuffer;

    public CreatureComponent(SequentialId id) {
        this.id = id;
        this.nextStimulusId = new SequentialId(id.sequential);
        logger = Logger.getLogger(this.getClass().getName());

        em = Persistence.createEntityManagerFactory("L2LPU")
                .createEntityManager();

        persistenceBuffer = new ArrayList<>();
    }

    @Override
    public void preStart() throws Exception {
        super.preStart();
        creature = TypedActor.get(context().system()).typedActorOf(
                new TypedProps<>(Creature.class, CreatureActor.class), context().parent());
        //bd = creature.bd();
    }

    @Override
    public void postStop() throws Exception {
        super.postStop();
        if (!persistenceBuffer.isEmpty()) {
            synchronized (em) {
                em.getTransaction().begin();
                persistenceBuffer.parallelStream().forEach(em::persist);
                em.getTransaction().commit();
            }

        }
    }

    void persist(PersistenceState ... states) {
        /*if (persistenceBuffer.size() > PERSISTENCE_BUFFER_LIMIT) {
            final List<PersistenceState> toPersist = new ArrayList<>(persistenceBuffer);
            final String className = this.getClass().getSimpleName();
            persistenceBuffer.clear();

            Runnable persistTask = () -> {
                logger.info("Persisting " + toPersist.size() + " states of " + className);
                synchronized (em) {
                    em.getTransaction().begin();
                    toPersist.parallelStream().forEach(em::persist);
                    em.getTransaction().commit();
                    em.clear();
                }
            };

            context().dispatcher().execute(persistTask);
        }

        persistenceBuffer.addAll(Arrays.asList(states));*/
        em.getTransaction().begin();
        for (PersistenceState state : states) {
            em.persist(state);
        }
        em.getTransaction().commit();
        em.clear();
    }

    SequentialId nextStimulusId() {
        SequentialId id = nextStimulusId;
        nextStimulusId = nextStimulusId.next();

        return id;
    }
}
