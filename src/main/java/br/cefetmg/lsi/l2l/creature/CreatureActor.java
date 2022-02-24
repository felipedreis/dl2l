package br.cefetmg.lsi.l2l.creature;

import akka.actor.*;
import static akka.pattern.PatternsCS.*;

import akka.japi.Creator;
import br.cefetmg.lsi.l2l.cluster.Sync;
import br.cefetmg.lsi.l2l.common.Constants;
import br.cefetmg.lsi.l2l.common.Pair;
import br.cefetmg.lsi.l2l.common.Point;
import br.cefetmg.lsi.l2l.common.SequentialId;
import br.cefetmg.lsi.l2l.creature.bd.BDActor;
import br.cefetmg.lsi.l2l.creature.bd.CreatureState;
import br.cefetmg.lsi.l2l.creature.components.*;
import br.cefetmg.lsi.l2l.creature.conditioning.OperantConditioning;
import br.cefetmg.lsi.l2l.creature.conditioning.OperantConditioningActor;
import br.cefetmg.lsi.l2l.creature.memory.MemorySystem;
import br.cefetmg.lsi.l2l.creature.memory.MemorySystemActor;
import br.cefetmg.lsi.l2l.physics.CreaturePositioningAttr;
import scala.concurrent.Future;
import scala.concurrent.duration.Duration;
import scala.concurrent.duration.FiniteDuration;

import javax.persistence.EntityManager;
import javax.persistence.Persistence;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Logger;

/**
 * Created by felipe on 02/01/17.
 */
public class CreatureActor implements Creature {

    public static TypedProps<CreatureActor> props(SequentialId id, ActorRef collisionDetector, Point position,
                                                  Point worldBoundaries) {
        return new TypedProps<>(Creature.class,
                (Creator<CreatureActor>) () -> new CreatureActor(id, collisionDetector, position, worldBoundaries)
        );
    }

    private static final Class [] componentTypes =  new Class  [] {
            Eye.class,
            Body.class,
            Mouth.class,
            Nose.class,
            SensoryCortex.class,
            EffectorCortex.class,
            PartialAppraisal.class,
            FullAppraisal.class,
            HomeostaticRegulation.class,
            Valuation.class,
    };

    private final Logger logger = Logger.getLogger(CreatureActor.class.getName());

    private final Point worldBoundaries;

    private final EntityManager em;

    private SequentialId id;

    private Map<Class, Pair<SequentialId, ActorRef>> components;

    private EmotionalSystem emotionalSystem;

    private OperantConditioning operantConditioning;

    private MemorySystem memory;

    private ActorRef bdActor;

    private boolean alive;
    private Point position;
    private double direction;
    private double visionFieldOpening;
    private double visionFieldPosition;
    private double olfactoryFieldRadius;

    private ActorRef collisionDetector;

    private CreatureState state;

    private Cancellable clock;

    public CreatureActor(SequentialId id, ActorRef collisionDetector, Point position, Point worldBoundaries){
        this.id = id;
        this.collisionDetector = collisionDetector;
        this.position = position;
        this.worldBoundaries = worldBoundaries;
        this.em = Persistence.createEntityManagerFactory("L2LPU").createEntityManager();
    }

    public void init() {
        ActorContext context = TypedActor.context();
        components = new HashMap<>();

        state = new CreatureState(id);
        state.setBornTime(System.currentTimeMillis());

        em.getTransaction().begin();
        em.persist(state);
        em.getTransaction().commit();

        alive = true;
        direction = 0;
        visionFieldOpening = Constants.MIN_VISION_FIELD_OPENING;
        olfactoryFieldRadius = Constants.MIN_OLFACTORY_FIELD_RADIUS;

        emotionalSystem = TypedActor.get(TypedActor.context())
                .typedActorOf(new TypedProps<>(EmotionalSystem.class, EmotionalSystemActor::new), "emotionalSystem");

        operantConditioning = TypedActor.get(TypedActor.context())
                .typedActorOf(new TypedProps<>(OperantConditioning.class, OperantConditioningActor::new),
                        "operantConditioning");

        memory = TypedActor.get(TypedActor.context())
                .typedActorOf(new TypedProps<>(MemorySystem.class, MemorySystemActor::new), "memorySystem");

        //bdActor = context.system().actorOf(Props.create(BDActor.class, em)
        //        .withDispatcher("bd-dispatcher"), "db");

        SequentialId componentId = id;

        for (Class componentType : componentTypes) {
            componentId = componentId.next();
            ActorRef component = context.actorOf(Props.create(componentType, componentId)
                    .withDispatcher("component-dispatcher"), componentType.getSimpleName().toLowerCase());
            components.put(componentType, new Pair<>(componentId, component) );
        }

        final ActorRef partial = components.get(PartialAppraisal.class).second;

        clock = TypedActor.context().system().scheduler()
                .schedule(Duration.apply(5, TimeUnit.SECONDS),
                        Duration.apply(1000, TimeUnit.MILLISECONDS), () -> {
                            partial.tell("", ActorRef.noSender());
                        }, TypedActor.context().dispatcher());

        collisionDetector.tell(getPositioningAttr(), ActorRef.noSender());
    }

    public void kill() {
        clock.cancel();
        state.setDeadTime(System.currentTimeMillis());

        for (Pair<SequentialId, ActorRef> p : components.values()) {
            TypedActor.context().stop(p.second);
        }

        //CompletionStage t = gracefulStop(bdActor, FiniteDuration.apply(120, "seconds"), PoisonPill.getInstance());
        //t.toCompletableFuture().thenRun(() -> {
            em.getTransaction().begin();
            em.persist(state);
            em.getTransaction().commit();
            em.close();
        //});

        /*try {
            t.toCompletableFuture().get(120, TimeUnit.SECONDS);
        } catch (Exception e) {
            e.printStackTrace();
        }*/

        logger.info("Sending remove order to holder");
        holder().tell(id, TypedActor.context().self());
        logger.info("Creature " + id + " killed");
    }

    @Override
    public ActorRef holder() {
        return TypedActor.context().parent();
    }

    private CreaturePositioningAttr getPositioningAttr() {
        return new CreaturePositioningAttr(id, componentId(Body.class), componentId(Eye.class), componentId(Nose.class),
                componentId(Mouth.class), position, visionFieldPosition, visionFieldOpening, olfactoryFieldRadius,
                false, false);
    }

    private SequentialId componentId(Class componentClass) {
        return components.get(componentClass).first;
    }

    private void updatePositioningAttribute() {
        ActorRef self = TypedActor.context().self();
        //logger.info("Updating creature positioning attr of " + self + " to " + collisionDetector);
        collisionDetector.tell(getPositioningAttr(), self);
    }

    public ActorRef eye() {
        return components.get(Eye.class).second;
    }

    public ActorRef body() {
        return components.get(Body.class).second;
    }

    public ActorRef mouth() {
        return components.get(Mouth.class).second;
    }

    public ActorRef nose() { return components.get(Nose.class).second; }

    public ActorRef sensoryCortex() {
        return components.get(SensoryCortex.class).second;
    }

    public ActorRef effectorCortex() {
        return components.get(EffectorCortex.class).second;
    }

    public ActorRef partialAppraisal() {
        return components.get(PartialAppraisal.class).second;
    }

    public ActorRef fullAppraisal() {
        return components.get(FullAppraisal.class).second;
    }

    public ActorRef homeostatic() {
        return components.get(HomeostaticRegulation.class).second;
    }

    public ActorRef valuation() {
        return components.get(Valuation.class).second;
    }

    @Override
    public EmotionalSystem emotions() {
        return emotionalSystem;
    }

    @Override
    public OperantConditioning operantConditioning() {
        return operantConditioning;
    }

    @Override
    public MemorySystem memory() {
        return memory;
    }

    @Override
    public ActorRef bd(){
        return bdActor;
    }

    public Point getPosition() {
        return position;
    }

    public void  setPosition(Point point) {
        double x, y;

        if (point.x > worldBoundaries.x)
            x = 0;
        else if (point.x < 0)
            x = worldBoundaries.x;
        else
            x = point.x;

        if(point.y > worldBoundaries.y)
            y = 0;
        else if (point.y < 0)
            y = worldBoundaries.y;
        else
            y = point.y;

        this.position = new Point(x, y);
        updatePositioningAttribute();
    }

    public void setVisionFieldOpening(double opening) {
        this.visionFieldOpening = opening;
        updatePositioningAttribute();
    }

    public double getVisionFieldOpening() {
        return visionFieldOpening;
    }

    public void setVisionFieldPosition(double arc) {
        this.visionFieldPosition = arc;
        updatePositioningAttribute();
    }

    public double getVisionFieldPosition() {
        return visionFieldPosition;
    }

    public void setAlive(boolean alive) {
        this.alive = alive;
        if(!alive)
            kill();
    }

    public boolean isAlive() {
        return alive;
    }

    public void setOlfactoryFieldRadius(double radius) {
        this.olfactoryFieldRadius = radius;
        updatePositioningAttribute();
    }

    public double getOlfactoryFieldRadius() {
        return olfactoryFieldRadius;
    }

}
