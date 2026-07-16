package br.cefetmg.lsi.l2l.cluster;

import akka.actor.ActorRef;
import akka.actor.PoisonPill;
import akka.actor.UntypedActor;
import akka.cluster.Cluster;
import br.cefetmg.lsi.l2l.cluster.settings.CreatureSetting;
import br.cefetmg.lsi.l2l.cluster.settings.Simulation;
import br.cefetmg.lsi.l2l.cluster.settings.WorldObjectSetting;
import br.cefetmg.lsi.l2l.common.SequentialId;
import br.cefetmg.lsi.l2l.metrics.MetricsExtension;
import br.cefetmg.lsi.l2l.world.WorldObjectType;
import org.apache.commons.collections4.ListUtils;
import scala.concurrent.duration.Duration;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
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
    private final int repositionDelaySeconds;

    private MetricsExtension.Impl metricsExt;

    SimulationManager(Simulation settings) {
        holders = new ArrayList<>();
        holdersCount = 0;
        holdersDone = new HashSet<>();
        this.settings = settings;
        this.maxHolders = settings.getNumHolders();
        this.repositionDelaySeconds = settings.getRepositionDelaySeconds();
    }

    private static final class MaxRuntimeExpired {}
    private record DelayedRepose(WorldObjectType objectType) {}

    @Override
    public void preStart() throws Exception {
        metricsExt = MetricsExtension.of(context().system());
        // See Holder.preStart()'s comment on the same call shape — the plain
        // varargs subscribe() defaults to InitialStateAsSnapshot, not
        // InitialStateAsEvents. Manager is always the first node up, so
        // there's rarely anything already-Up to miss here in practice, but
        // fixed for correctness/consistency with the other 3 call sites.
        cluster.subscribe(self(), initialStateAsEvents(), MemberUp.class, MemberJoined.class, MemberExited.class);
        int maxRuntime = settings.getMaxRuntimeMinutes();
        if (maxRuntime > 0) {
            context().system().scheduler().scheduleOnce(
                    Duration.create(maxRuntime, TimeUnit.MINUTES),
                    self(), new MaxRuntimeExpired(),
                    context().dispatcher(), self());
            logger.info("Simulation runtime limited to " + maxRuntime + " minutes");
        }
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
            if (repositionDelaySeconds > 0) {
                context().system().scheduler().scheduleOnce(
                        Duration.create(repositionDelaySeconds, TimeUnit.SECONDS),
                        self(), new DelayedRepose(repose.objectType()),
                        context().dispatcher(), ActorRef.noSender());
            } else {
                spawnWorldObject(repose.objectType());
            }
        } else if (message instanceof DelayedRepose) {
            spawnWorldObject(((DelayedRepose) message).objectType());

        } else if (message instanceof AllCreaturesDead) {
            AllCreaturesDead holder = (AllCreaturesDead) message;
            logger.info("All creatures dead in holder " + holder.id());
            holdersDone.add(holder.id());

            if (holdersDone.size() == maxHolders)
                stopSimulation();
        } else if (message instanceof MaxRuntimeExpired) {
            logger.info("Maximum runtime reached — stopping simulation");
            stopSimulation();
        }
    }

    private void spawnWorldObject(WorldObjectType type) {
        SequentialId id = Sync.ask(idProvider, new AskForId(), 5);
        holders.get((int)(id.key % maxHolders))
                .tell(new CreateWorldObject(type, id), self());
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
        metricsExt.setGauge("dl2l_simulation_running", 1);

        boolean everybodyReady = true;

        for(ActorRef holder : holders) {
            Object x = Sync.ask(holder, new AckReady(), TIMEOUT);
            everybodyReady = everybodyReady && ((Ready) x).ready();
        }

        if (!everybodyReady)
            throw new IllegalStateException("There were a problem setting up the holders, you may restart everybody");

        logger.info("Starting all world objects — worldObjectSettings.size=" + settings.getWorldObjectSettings().size() + " holders.size=" + holders.size() + " maxHolders=" + maxHolders);

        for (WorldObjectSetting objectSetting : settings.getWorldObjectSettings()) {
            List<SequentialId> objectIds = Sync.ask(idProvider, new AskForIds(objectSetting.getQuantity()), TIMEOUT);

            int chunkSize = (objectIds.size() + holders.size() - 1) / holders.size();
            List<List<SequentialId>> idsPerHolder = ListUtils.partition(objectIds, chunkSize);

            for (int i = 0; i < maxHolders; ++i) {
                ActorRef holder = holders.get(i);
                logger.info("Telling CreateWorldObjects type=" + objectSetting.getType() + " count=" + idsPerHolder.get(i).size() + " to " + holder);
                holder.tell(new CreateWorldObjects(objectSetting.getType(), idsPerHolder.get(i).stream().toList()), self());
            }
        }

        logger.info("All world objects created");
        logger.info("Starting all creatures");

        for (CreatureSetting creatureSetting : settings.getCreatureSettings()) {
            List<SequentialId> creatureIds = Sync.ask(idProvider, new AskForIds(creatureSetting.getQuantity()), TIMEOUT);
            for (SequentialId id : creatureIds) {
                ActorRef holder = holders.get((int) (id.key % maxHolders));
                logger.info("Telling CreateCreature id=" + id + " to " + holder);
                holder.tell(new CreateCreature(id), self());
            }
        }

        logger.info("All creatures created");
    }

    private void stopSimulation() {
        metricsExt.setGauge("dl2l_simulation_running", 0);
        idProvider.tell(new Finish(), self());
        collisionDetector.tell(new Finish(), self());

        for (ActorRef holder : holders) {
            holder.tell(new Finish(), self());
        }

        // TRIED delaying this by a few seconds (scheduler.scheduleOnce) so the
        // /metrics HTTP server would have a real window to serve the 0 reading
        // before disappearing — CONFIRMED LIVE (local docker-compose, 2026-07-15)
        // this backfires: something in Akka's own shutdown path (cluster member
        // removal triggers coordinated shutdown, independent of this actor) tears
        // the ActorSystem down almost immediately regardless, which force-runs the
        // scheduler's pending tasks early during `LightArrayRevolverScheduler.close()`
        // — by then this actor's own context() is already null, so the delayed
        // callback NPEs (`Cannot invoke "ActorContext.stop(ActorRef)" because the
        // return value of "context()" is null`). Not worth fighting Akka's own
        // shutdown machinery for this: the gauge flipping to 0 is still real and
        // correct the instant it's set below, just not reliably observable
        // externally afterward — run_trial.sh.j2's metrics check is opportunistic
        // (falls through to the log-grep fallback when it doesn't catch it), so
        // this is an accepted, harmless limitation rather than a bug to chase.
        context().stop(self());
        context().system().terminate();
    }

}
