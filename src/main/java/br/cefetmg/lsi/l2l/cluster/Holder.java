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
import br.cefetmg.lsi.l2l.creature.bd.DumpParquet;
import br.cefetmg.lsi.l2l.creature.bd.Flush;
import br.cefetmg.lsi.l2l.creature.bd.PersistenceExtension;
import br.cefetmg.lsi.l2l.creature.ml.MLServiceExtension;
import br.cefetmg.lsi.l2l.metrics.MetricsExtension;
import br.cefetmg.lsi.l2l.cluster.settings.LearningSettings;
import br.cefetmg.lsi.l2l.cluster.settings.Simulation;
import br.cefetmg.lsi.l2l.common.Point;
import br.cefetmg.lsi.l2l.common.SequentialId;
import br.cefetmg.lsi.l2l.creature.Creature;
import br.cefetmg.lsi.l2l.creature.CreatureActor;
import br.cefetmg.lsi.l2l.stimuli.ExternalStimulus;
import br.cefetmg.lsi.l2l.world.Fruit;
import br.cefetmg.lsi.l2l.world.FruitType;
import br.cefetmg.lsi.l2l.world.Plant;
import br.cefetmg.lsi.l2l.world.PlantType;
import br.cefetmg.lsi.l2l.world.PositionFactory;
import br.cefetmg.lsi.l2l.world.WorldObjectType;
import scala.concurrent.Await;
import scala.concurrent.Future;
import scala.concurrent.duration.Duration;

import java.util.HashMap;
import java.util.Map;
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

    private LearningSettings learningSettings;

    private MetricsExtension.Impl metricsExt;

    // Issue #79: at the ~5x cognitive-cycle throughput measured post-#76/#78 (see
    // docs/reports/p79_metabolic_clock_report.md), BDActor's backlog reached 1.4-2.5M
    // queued states by the time the last of just 3 short-lived (~15-30s) creatures died,
    // and draining it (at BDActor's measured ~17-18K states/s sustained rate - a raw
    // Postgres write-throughput ceiling that neither JDBC batch-writing nor larger
    // bd-dispatcher batch caps moved) took 97-155s. The old 30s timeout threw a
    // TimeoutException before that finished, which aborted handleRemoveObject/handleFinish
    // before AllCreaturesDead ever reached SimulationManager - the holder then never
    // received a Finish and hung forever (docker wait on it never returns). 30 minutes
    // gives headroom for creatures living the full ~150s Phase A targets at the same
    // cycle rate (proportionally larger backlog) while still failing loudly if BDActor
    // ever genuinely stalls rather than just being slow.
    private static final int FLUSH_TIMEOUT_SECONDS = 1800;

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
        learningSettings = settings.getLearningSettings();
    }

    @Override
    public void preStart() throws Exception {
        super.preStart();
        metricsExt = MetricsExtension.of(context().system());
        // CONFIRMED LIVE (2026-07-16): the plain 2-arg subscribe(self(),
        // MemberUp.class) defaults to InitialStateAsSnapshot, not
        // InitialStateAsEvents (verified by decompiling the actual
        // akka-cluster_2.11-2.5.32.jar this project bundles) — if manager
        // is ALREADY Up by the time this line runs, this actor gets a
        // CurrentClusterState snapshot instead of a MemberUp event for it,
        // which handleNewMember() below never sees (onReceive only matches
        // MemberUp.class), so the Register message to manager never gets
        // sent. Manager is always started first and its TCP port is
        // confirmed open before this JVM even starts, so it's frequently
        // already Up by now — under light load this actor's own startup is
        // fast enough to usually still land inside the pre-Up window, but
        // under heavy concurrent CPU contention (many trials sharing a
        // node) startup is slow and variable enough that manager is Up far
        // more often than not, which is exactly why this failure mode went
        // from "occasional" to "most trials" as concurrency increased in
        // testing. initialStateAsEvents() makes Akka synthesize a MemberUp
        // event for every already-Up member at subscribe time, closing the
        // race regardless of relative startup timing.
        cluster.subscribe(self(), initialStateAsEvents(), MemberUp.class);
        logger.setLevel(Level.SEVERE);
        // Register learning settings before any creature is spawned so components can read them.
        SimulationSettingsExtension.of(context().system()).configure(learningSettings);
        // Sets up the persistence backend (see PersistenceExtension) before any creature is
        // spawned - CreatureActor/component actors resolve bdActor() in their own preStart().
        PersistenceExtension.of(context().system()).configure(saveDir);
        // Eagerly load the species ML models once for this JVM node, so an invalid model
        // contract fails fast here rather than mid-run. Skipped entirely when the filter
        // chain has no WORLD_MODEL: nothing in the run would touch the models, and loading
        // them costs a DJL/PyTorch native-runtime download plus hundreds of MB for nothing.
        // See CreatureActor.worldModelInUse().
        if (CreatureActor.worldModelInUse(learningSettings)) {
            MLServiceExtension.of(context().system());
        }
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
            logger.fine("Created a new world object with id " + id);
        } else if (type instanceof PlantType) {
            ActorRef worldObject = context().actorOf(
                    Plant.props(id, type, factory.nextPosition(), collisionDetector),
                    "object-" + id.toString());

            worldObjects.put(id, worldObject);
            worldObjecttypes.put(id, type);
            logger.fine("Created a new plant world object with id " + id);
        }
    }

    private void handleCreateCreature(CreateCreature order) {
        logger.info("Handling new creature creation order %s".formatted(order));
        Creature creature = TypedActor.get(context()).typedActorOf(
                CreatureActor.props(order.id(), collisionDetector, factory.nextPosition(), worldBoundaries,
                        order.learningSettings()),
                "creature-" + order.id());
        creature.init();
        creatures.put(order.id(), creature);
        metricsExt.setGauge("dl2l_creatures_alive", creatures.size());
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
                logger.fine("Stimulus routed to a component in current holder " + targetComp);
            }

        } else  if(holders.containsKey(holderId)){
            ActorRef targetHolder = holders.get(holderId);
            targetHolder.tell(stimulus, sender());
            logger.fine("Stimulus routed to a known holder " + targetHolder);
        } else {
            ActorRef holder = lookupHolder(holderId);
            holder.tell(stimulus, sender());
            holders.put(holderId, holder);
            logger.fine("Unknown holder. Holder lookup executed with success and stimulus routed");
        }
    }

    private void handleRemoveObject(SequentialId id) {
        ActorRef componentActor = null;

        if(creatures.containsKey(id)) {
            componentActor = TypedActor.get(context()).getActorRefFor(creatures.get(id));
            creatures.remove(id);
            metricsExt.setGauge("dl2l_creatures_alive", creatures.size());

            if(creatures.isEmpty()) {
                // Wait for every write already in BDActor's mailbox to commit before
                // reporting the holder's work as done - persistence is async
                // (creature.bd().tell(state)), so without this drain the Postgres data
                // extracted afterward (scripts/dl2l_data.extract, Python-side) could be
                // missing each creature's final ticks. See
                // docs/plans/bdactor-async-persistence-with-drain.md §4a.
                //
                // Issue #79: this used to also run DataAnalyser (JPA read queries across
                // ~20 Extractor classes producing legacy per-creature CSVs) here - nothing
                // in the current pipeline (ansible/dl2l_data, analysis/dl2l_analysis) ever
                // read those CSVs, and both DataAnalyser and the extractor classes have
                // since been deleted entirely (JPA is gone from this JVM, see
                // docs/plans/remove-jpa-persistence-layer.md). Extraction is Python-side,
                // now reading the Parquet files BDActor dumps at shutdown (see
                // PersistenceExtension.of(...).bdActor(), docs/plans/parquet-write-path.md)
                // instead of querying a live database.
                Sync.ask(PersistenceExtension.of(context().system()).bdActor(), new Flush(), FLUSH_TIMEOUT_SECONDS);

                manager.tell(new AllCreaturesDead(this.id), self());
                logger.info("This holder has finished his work.");
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
            logger.fine("There is no simulation component with identified as " + id);
        }
        if(componentActor != null)
            context().stop(componentActor);

        collisionDetector.tell(id, self());

        logger.fine("Removed simulation component " + id);
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
        // Defensive second drain: cheap and idempotent (nothing left to flush in the common
        // case, since handleRemoveObject's drain already ran) - covers a holder that never
        // held a creature, or any future persistence path that doesn't go through the
        // per-creature-death drain. See docs/plans/bdactor-async-persistence-with-drain.md §4b.
        Sync.ask(PersistenceExtension.of(context().system()).bdActor(), new Flush(), FLUSH_TIMEOUT_SECONDS);
        // Every write is durably committed as of the Flush above - finalize each raw table's
        // Parquet file now (see docs/plans/parquet-write-path.md).
        // scripts/dl2l_data/extract.py reads these directly instead of querying a live
        // database.
        Sync.ask(PersistenceExtension.of(context().system()).bdActor(), new DumpParquet(), FLUSH_TIMEOUT_SECONDS);
        for(ActorRef component : worldObjects.values()) {
            context().stop(component);
        }
        context().stop(self());
        context().system().terminate();
    }
}
