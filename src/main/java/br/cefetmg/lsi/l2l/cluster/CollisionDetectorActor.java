package br.cefetmg.lsi.l2l.cluster;

import akka.actor.*;
import akka.cluster.Cluster;
import akka.cluster.ClusterEvent.*;
import akka.cluster.Member;
import akka.japi.pf.ReceiveBuilder;
import akka.util.Timeout;
import br.cefetmg.lsi.l2l.cluster.settings.Simulation;
import br.cefetmg.lsi.l2l.common.SequentialId;
import br.cefetmg.lsi.l2l.creature.Creature;
import br.cefetmg.lsi.l2l.creature.CreatureActor;
import br.cefetmg.lsi.l2l.physics.CreatureGeometry;
import br.cefetmg.lsi.l2l.physics.CreaturePositioningAttr;
import br.cefetmg.lsi.l2l.physics.ObjectGeometry;
import br.cefetmg.lsi.l2l.physics.WorldObjectPositioningAttr;
import br.cefetmg.lsi.l2l.stimuli.*;
import scala.concurrent.Await;
import scala.concurrent.Future;
import scala.concurrent.duration.Duration;

import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Created by felipe on 06/01/17.
 */
public class CollisionDetectorActor extends AbstractActor implements Registrable {
    private final Cluster cluster = Cluster.get(context().system());

    private final Logger logger = Logger.getLogger(CollisionDetectorActor.class.getName());

    private Map<SequentialId, CreatureGeometry> creatureAttrs;
    private Map<SequentialId, ObjectGeometry> objectAttrs;

    private ActorRef gui;

    private Simulation settings;

    public CollisionDetectorActor(ActorRef gui, Simulation settings) {
        this.gui = gui;
        creatureAttrs = new HashMap<>();
        objectAttrs = new HashMap<>();
        this.settings = settings;
    }

    @Override
    public void preStart() throws Exception {
        super.preStart();
        cluster.subscribe(self(), MemberUp.class);
    }

    @Override
    public void postStop() throws Exception {
        super.postStop();
        cluster.unsubscribe(self());
        logger.info("Collision detector stopped");
    }

    @Override
    public Receive createReceive() {
        return ReceiveBuilder.create()
                .match(CreaturePositioningAttr.class, attr -> {
                    if(sender().path().toString().contains("deadLetter")) {
                        logger.severe("Sender is dead letter, will not be handled");
                        return;
                    }

                    Creature creature = TypedActor.get(context())
                            .typedActorOf(new TypedProps<>(CreatureActor.class), sender());

                    CreatureGeometry geometry = new CreatureGeometry(attr);
                    creatureAttrs.put(attr.creatureId, geometry);
                    checkCreatureCollisions(geometry, creature);

                    updateGui(geometry);
                })
                .match(WorldObjectPositioningAttr.class, attr -> {
                    logger.info("Received a world object positioning attribute update");

                    ObjectGeometry geometry = new ObjectGeometry(attr);
                    objectAttrs.put(attr.id, geometry);

                    updateGui(geometry);
                })
                .match(SequentialId.class, id -> {
                    logger.info("A world object or creature (" + id + ") was removed");

                    if(objectAttrs.containsKey(id))
                        objectAttrs.remove(id);
                    else if(creatureAttrs.containsKey(id))
                        creatureAttrs.remove(id);

                    updateGui(id);
                })
                .match(MemberUp.class, memberUp -> {
                    handleNewMember(memberUp.member());
                })
                .match(Finish.class, finish -> {
                    logger.info("Got stop order from  master");
                    context().stop(self());
                    if (gui != null)
                        context().stop(gui);

                    context().system().terminate();
                    updateGui(finish);
                })
                .build();
    }

    private void updateGui(Object msg) {
        if(gui != null)
            gui.tell(msg, self());
    }

    @Override
    public void handleNewMember(Member member) {
        if(member.hasRole("manager")) {
            context().actorSelection(member.address() + "/user/manager")
                    .tell(new Register("collisionDetector"), self());
            logger.info("Registering to manager");
        }
    }


    private void checkCreatureCollisions(CreatureGeometry geom, Creature creature){
        try {
            if (TypedActor.get(context()).getActorRefFor(creature).isTerminated())
                System.exit(0);
            /// TODO implement a collision tree
            for (CreatureGeometry other : creatureAttrs.values()) {
                if (geom.body.intersects(other.body)) {
                } else if (geom.body.intersects(other.visionField)) {
                } else if (geom.body.intersects(other.olfactoryField)) {
                } else if (geom.visionField.intersects(geom.body)) {
                }
            }

            objectAttrs.forEach((id, obj) -> {
                Stimulus sentStimulus = null;
                if (geom.body.intersects(obj.shape)) {
                    // TODO rename the TouchStimulus to Mechanical according to Campos (2015) version
                    ///sentStimulus = new TouchStimulus(id);
                    //creature.body().tell(sentStimulus, self());
                }
                if (geom.visionField.intersects(obj.shape)) {
                    sentStimulus = new LuminousStimulus(id, null, obj.type, obj.point);
                    creature.eye().tell(sentStimulus, self());
                }
                if (geom.mouth.intersects(obj.shape)) {
                    sentStimulus = new MechanicalStimulus(id, null, obj.type);
                    creature.mouth().tell(sentStimulus, self());
                }
                if (geom.olfactoryField.intersects(obj.shape)) {
                    // TODO create a nose
                    sentStimulus = new SmellStimulus(id, null, obj.type, obj.point);
                    creature.nose().tell(sentStimulus, self());
                }
            });
        } catch (Exception ex) {
            logger.log(Level.WARNING, ex.getMessage(), ex);
        }
    }
}
