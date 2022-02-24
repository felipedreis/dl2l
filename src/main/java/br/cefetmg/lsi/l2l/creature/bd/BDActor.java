package br.cefetmg.lsi.l2l.creature.bd;

import akka.actor.PoisonPill;
import akka.actor.UntypedActor;

import javax.persistence.EntityManager;
import javax.persistence.Persistence;
import java.util.List;
import java.util.logging.Logger;

/**
 * Created by felipe on 15/05/17.
 */
public class BDActor extends UntypedActor {

    private EntityManager em = Persistence.createEntityManagerFactory("L2LPU").createEntityManager();

    private Logger logger = Logger.getLogger(BDActor.class.getSimpleName());

    public BDActor(EntityManager em) {
        this.em = em;
    }

    @Override
    public void onReceive(Object message) throws Exception {
        if (message instanceof List) {
            List states = (List) message;
            logger.info("Persisting " + states.size() + " states");

            em.getTransaction().begin();
            for (Object state : states) {
                if (state instanceof PersistenceState)
                    em.persist(state);
            }
            em.getTransaction().commit();

        } else if (message instanceof PoisonPill) {
            getContext().stop(self());
            logger.info("BDActor gonna stop");
        } else
            unhandled(message);
    }

    @Override
    public void postStop() throws Exception {
        super.postStop();

        if(em.getTransaction().isActive())
            em.getTransaction().commit();
    }
}
