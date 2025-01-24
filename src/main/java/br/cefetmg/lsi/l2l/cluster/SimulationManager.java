package br.cefetmg.lsi.l2l.cluster;

import akka.actor.ActorRef;
import akka.actor.PoisonPill;
import akka.actor.UntypedActor;
import akka.cluster.Cluster;
import br.cefetmg.lsi.l2l.cluster.settings.CreatureSetting;
import br.cefetmg.lsi.l2l.cluster.settings.Simulation;
import br.cefetmg.lsi.l2l.cluster.settings.WorldObjectSetting;
import br.cefetmg.lsi.l2l.common.SequentialId;
import org.apache.commons.collections4.ListUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

import static akka.cluster.ClusterEvent.*;

/**
 * Created by felipe on 27/03/17.
 */
public class SimulationManager extends UntypedActor {

    private Cluster cluster = Cluster.get(context().system());

    private Logger logger = Logger.getLogger(SimulationManager.class.getName());

    private static final int TIMEOUT = 60;

    private Simulation settings;

    private ActorRef collisionDetector;
    private ActorRef idProvider;

    private List<ActorRef> holders;
    private Set<Long> holdersDone;
    private long holdersCount;
    private final long maxHolders;

    SimulationManager(Simulation settings) {
        holders = new ArrayList<>();
        holdersCount = 0;
        holdersDone = new HashSet<>();
        this.settings = settings;
        this.maxHolders = settings.getNumHolders();
    }

    @Override
    public void preStart() throws Exception {
        cluster.subscribe(self(), MemberUp.class, MemberJoined.class, MemberExited.class);
    }

    @Override
    public void postStop() throws Exception {
        cluster.unsubscribe(self());
        logger.info("Manager stopped");
    }

    @Override
    public void onReceive(Object message)  {
        if (message instanceof Register) {
            handleRegister((Register) message);
        } else if (message instanceof HolderLookup) {
            HolderLookup lookup = (HolderLookup) message;
            sender().tell(holders.get((int) lookup.id()), self());

            logger.info("Got a lookup for " + lookup.id());

        } else if (message instanceof Repose) {
            Repose repose = (Repose) message;
            SequentialId id = Sync.ask(idProvider, new AskForId(), 5);
            holders.get((int)(id.key % maxHolders))
                    .tell(new CreateWorldObject(repose.objectType(), id), self());

        } else if (message instanceof AllCreaturesDead) {
            AllCreaturesDead holder = (AllCreaturesDead) message;
            logger.info("All creatures dead in holder " + holder.id());
            holdersDone.add(holder.id());

            if (holdersDone.size() == maxHolders)
                stopSimulation();
        }
    }

    private void handleRegister(Register register) {
        switch (register.role()) {
            case "holder" :
                ActorRef holder = sender();

                if (holdersCount < maxHolders) {
                    holders.add((int) holdersCount, holder);
                    holder.tell(holdersCount, self());
                    holdersCount++;

                    logger.info("Registering a new holder");
                } else {
                    //TODO say there is no other holder permitted
                    sender().tell(PoisonPill.getInstance(), self());
                }

                if (holdersCount == maxHolders) {
                    logger.info("Holders count achieved the expected value");
                    try {
                        Thread.sleep(5000);
                        startSimulation();
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
                break;

            case "idProvider":
                idProvider = sender();
                logger.info("IdProvider " + idProvider +  " registered");
                break;

            case "collisionDetector":
                collisionDetector = sender();
                logger.info("Collision detector " + collisionDetector + "registered");

        }

    }

    private void startSimulation() throws IllegalStateException {
        logger.info("Starting the simulation");

        boolean everybodyReady = true;

        for(ActorRef holder : holders) {
            Object x = Sync.ask(holder, new AckReady(), TIMEOUT);
            everybodyReady = everybodyReady && ((Ready) x).ready();
        }

        if (!everybodyReady)
            throw new IllegalStateException("There were a problem setting up the holders, you may restart everybody");

        logger.info("Starting all world objects");

        for (WorldObjectSetting objectSetting : settings.getWorldObjectSettings()) {
            List<SequentialId> objectIds = Sync.ask(idProvider, new AskForIds(objectSetting.getQuantity()), TIMEOUT);

            List<List<SequentialId>> idsPerHolder = ListUtils.partition(objectIds, holders.size());

            for (int i = 0; i < maxHolders; ++i) {
                ActorRef holder = holders.get(i);
                holder.tell(new CreateWorldObjects(objectSetting.getType(), idsPerHolder.get(i).stream().toList()), self());
            }
        }

        logger.info("All world objects created");
        logger.info("Starting all creatures");

        for (CreatureSetting creatureSetting : settings.getCreatureSettings()) {
            List<SequentialId> creatureIds = Sync.ask(idProvider, new AskForIds(creatureSetting.getQuantity()), TIMEOUT);
            for (SequentialId id : creatureIds) {
                ActorRef holder = holders.get((int) (id.key % maxHolders));
                holder.tell(new CreateCreature(id), self());
            }
        }

        logger.info("All creatures created");
    }

    private void stopSimulation() {
        idProvider.tell(new Finish(), self());
        collisionDetector.tell(new Finish(), self());

        for (ActorRef holder : holders) {
            holder.tell(new Finish(), self());
        }

        context().stop(self());
        context().system().terminate();
    }

}
