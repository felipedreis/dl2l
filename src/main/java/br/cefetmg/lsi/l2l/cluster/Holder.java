package br.cefetmg.lsi.l2l.cluster;

import akka.actor.AbstractActor;
import akka.actor.ActorRef;
import akka.actor.TypedActor;
import akka.cluster.Cluster;

import static akka.cluster.ClusterEvent.*;

import akka.cluster.Member;
import akka.japi.pf.ReceiveBuilder;
import akka.pattern.Patterns;
import akka.util.Timeout;
import br.cefetmg.lsi.l2l.analysis.DataAnalyser;
import br.cefetmg.lsi.l2l.cluster.settings.Simulation;
import br.cefetmg.lsi.l2l.common.Point;
import br.cefetmg.lsi.l2l.common.SequentialId;
import br.cefetmg.lsi.l2l.creature.Creature;
import br.cefetmg.lsi.l2l.creature.CreatureActor;
import br.cefetmg.lsi.l2l.stimuli.ExternalStimulus;
import br.cefetmg.lsi.l2l.world.Fruit;
import br.cefetmg.lsi.l2l.world.FruitType;
import br.cefetmg.lsi.l2l.world.PositionFactory;
import br.cefetmg.lsi.l2l.world.WorldObjectType;
import scala.concurrent.Await;
import scala.concurrent.Future;
import scala.concurrent.duration.Duration;

import javax.persistence.EntityManager;
import javax.persistence.Persistence;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Created by felipe on 27/03/17.
 */
public class Holder extends AbstractActor implements Registrable {

    final private Cluster cluster = Cluster.get(context().system());

    final private Logger logger = Logger.getLogger(Holder.class.getName());

    private long id = -1;
    private final long numHolders;

    private ActorRef collisionDetector;
    private ActorRef manager;

    private Map<SequentialId, Creature> creatures;
    private Map<SequentialId, ActorRef> worldObjects;
    private Map<SequentialId, WorldObjectType> worldObjecttypes;
    private Map<Long, ActorRef> holders;

    private final Point worldBoundaries;

    private PositionFactory factory;

    private String saveDir;

    private boolean hasReposition;

    public Holder(Simulation settings, String saveDir) {
        this.saveDir = saveDir;

        creatures = new HashMap<>();
        worldObjects = new HashMap<>();
        worldObjecttypes = new HashMap<>();
        holders = new HashMap<>();

        factory = settings.getPositionFactory();
        numHolders = settings.getNumHolders();
        worldBoundaries = settings.getWorldBoundaries();
        hasReposition = settings.isReposition();
    }

    @Override
    public void preStart() throws Exception {
        super.preStart();
        cluster.subscribe(self(), MemberUp.class);
        logger.setLevel(Level.SEVERE);
    }

    @Override
    public void postStop() throws Exception {
        super.postStop();
        cluster.unsubscribe(self());
        logger.info("Holder stopped");
    }

    @Override
    public Receive createReceive() {
        return ReceiveBuilder.create()
                .match(Long.class, this::handleId)
                .match(MemberUp.class, memberUp -> handleNewMember(memberUp.member()))
                .match(AckReady.class, this::handleReady)
                .match(CreateWorldObject.class, this::handleCreateWorldObject)
                .match(CreateWorldObjects.class, this::handleCreateWorldObjects)
                .match(CreateCreature.class, this::handleCreateCreature)
                .match(ExternalStimulus.class, this::handleExternalStimulus)
                .match(SequentialId.class, this::handleRemoveObject)
                .match(Finish.class, this::handleFinish)
                .build();
    }

    private void handleId(Long id) {
        this.id = id;
        manager = sender();

        logger.info("Got id: " + id);
    }

    private void handleReady(AckReady ready) {
        if(id >= 0 && collisionDetector != null && manager != null) {
            sender().tell(new Ready(true), self());
            logger.info("Ack and ready");
        }
        else {
            sender().tell(new Ready(false), self());
            logger.info("Ack but not ready");
        }
    }

    private void handleCreateWorldObjects(CreateWorldObjects order) {
        logger.info("Got new creation order for " + order.type());
        order.id().forEach(id -> createWorldObject(order.type(), id));
    }

    private void handleCreateWorldObject(CreateWorldObject order) {
        logger.info("Got new creation order for " + order.type());
        createWorldObject(order.type(), order.id());
    }

    private void createWorldObject(WorldObjectType type, SequentialId id) {
        if(type instanceof FruitType) {
            ActorRef worldObject = context().actorOf(
                    Fruit.props(id, type, factory.nextPosition(), collisionDetector),
                    "object-" + id.toString());

            worldObjects.put(id, worldObject);
            worldObjecttypes.put(id, type);
            logger.info("Created a new world object with id " + id);
        }
    }

    private void handleCreateCreature(CreateCreature order) {
        Creature creature = TypedActor.get(context()).typedActorOf(
                CreatureActor.props(order.id(), collisionDetector, factory.nextPosition(), worldBoundaries),
                "creature-" + order.id());
        creature.init();
        creatures.put(order.id(), creature);
        logger.info("Created a new creature");
    }

    private void handleExternalStimulus(ExternalStimulus stimulus) {
        long holderId = stimulus.target.key % numHolders;

        if(holderId == id) {
            ActorRef targetComp = null;

            if(creatures.containsKey(stimulus.target)){
                // TODO create a method in CreatureActor interface to handle external stimuli
            } else if (worldObjects.containsKey(stimulus.target)) {
                targetComp = worldObjects.get(stimulus.target);
                targetComp.tell(stimulus, sender());
                logger.info("Stimulus routed to a component in current holder " + targetComp);
            }

        } else  if(holders.containsKey(holderId)){
            ActorRef targetHolder = holders.get(holderId);
            targetHolder.tell(stimulus, sender());
            logger.info("Stimulus routed to a known holder " + targetHolder);
        } else {
            ActorRef holder = lookupHolder(holderId);
            holder.tell(stimulus, sender());
            holders.put(holderId, holder);
            logger.info("Unknown holder. Holder lookup executed with success and stimulus routed");
        }
    }

    private void handleRemoveObject(SequentialId id) {
        ActorRef componentActor = null;

        if(creatures.containsKey(id)) {
            componentActor = TypedActor.get(context()).getActorRefFor(creatures.get(id));
            creatures.remove(id);

            if(creatures.isEmpty()) {
                EntityManager em = Persistence.createEntityManagerFactory("L2LPU")
                        .createEntityManager();

                DataAnalyser analyser = new DataAnalyser(em,  saveDir);
                CompletableFuture all = analyser.run();

                all.thenRun(() -> {
                    manager.tell(new AllCreaturesDead(this.id), self());
                    logger.info("This holder has finished his work.");
                });
            }

            logger.info("Removed a creature");
        } else if (worldObjects.containsKey(id)) {
            componentActor = worldObjects.get(id);
            worldObjects.remove(id);
            WorldObjectType type = worldObjecttypes.remove(id);

            if (hasReposition) {
                manager.tell(new Repose(type), self());
            }
        } else {
            logger.info("There is no simulation component with identified as " + id);
        }
        if(componentActor != null)
            context().stop(componentActor);

        collisionDetector.tell(id, self());

        logger.info("Removed simulation component " + id);
    }

    @Override
    public void handleNewMember(Member member) {
        if(member.hasRole("manager")) {
            context().actorSelection(member.address().toString() + "/user/manager").
                    tell(new Register("holder"), self());

            logger.info("Registering to a manager");
        }

        if (member.hasRole("collisionDetector")) {
            try {
                Timeout timeout = new Timeout(Duration.create(1, "second"));
                Future<ActorRef> future = context()
                        .actorSelection(member.address().toString() + "/user/collisionDetector")
                        .resolveOne(timeout);
                collisionDetector = Await.result(future, timeout.duration());
                logger.info("Found a collision detector");
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private ActorRef lookupHolder(long id) {
        try {
            Timeout timeout = new Timeout(Duration.create(1, "second"));
            Future future = Patterns.ask(manager, new HolderLookup(id), timeout);
            return (ActorRef) Await.result(future, timeout.duration());
        } catch (Exception ex) {
            ex.printStackTrace();
        }
        return null;
    }

    private void handleFinish(Finish finish) {
        logger.info("Received stop order from manager. Stopping ");
        for(ActorRef component : worldObjects.values()) {
            context().stop(component);
        }
        context().stop(self());
        context().system().terminate();
    }
}
