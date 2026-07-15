package br.cefetmg.lsi.l2l.creature;

import akka.actor.*;

import akka.japi.Creator;
import br.cefetmg.lsi.l2l.cluster.SimulationSettingsExtension;
import br.cefetmg.lsi.l2l.cluster.settings.LearningSettings;
import br.cefetmg.lsi.l2l.common.Constants;
import br.cefetmg.lsi.l2l.common.Pair;
import br.cefetmg.lsi.l2l.common.Point;
import br.cefetmg.lsi.l2l.common.SequentialId;
import br.cefetmg.lsi.l2l.creature.bd.CreatureState;
import br.cefetmg.lsi.l2l.creature.components.*;
import br.cefetmg.lsi.l2l.creature.conditioning.OperantConditioning;
import br.cefetmg.lsi.l2l.creature.conditioning.OperantConditioningActor;
import br.cefetmg.lsi.l2l.creature.actionSelector.WorldModelFilter;
import br.cefetmg.lsi.l2l.creature.conditioning.expectancy.ExpectancyMode;
import br.cefetmg.lsi.l2l.creature.conditioning.expectancy.ExpectancyPredictor;
import br.cefetmg.lsi.l2l.creature.conditioning.expectancy.ExpectancyPredictors;
import br.cefetmg.lsi.l2l.creature.conditioning.expectancy.JepaExpectancyPredictor;
import br.cefetmg.lsi.l2l.creature.memory.MemorySystem;
import br.cefetmg.lsi.l2l.creature.memory.MemorySystemActor;
import br.cefetmg.lsi.l2l.creature.bd.ActionSelectionType;
import br.cefetmg.lsi.l2l.creature.ml.MLServiceExtension;
import br.cefetmg.lsi.l2l.creature.ml.MemoryConsolidator;
import br.cefetmg.lsi.l2l.creature.ml.MemoryTraceConsolidator;
import br.cefetmg.lsi.l2l.metrics.MetricsExtension;
import br.cefetmg.lsi.l2l.physics.CreaturePositioningAttr;
import scala.concurrent.duration.Duration;

import javax.persistence.EntityManager;
import javax.persistence.Persistence;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.logging.Logger;

/**
 * Created by felipe on 02/01/17.
 */
public class CreatureActor implements Creature {

    public static TypedProps<CreatureActor> props(SequentialId id, ActorRef collisionDetector, Point position,
                                                  Point worldBoundaries) {
        return new TypedProps<>(Creature.class,
                (Creator<CreatureActor>) () -> new CreatureActor(id, collisionDetector, position, worldBoundaries, null)
        );
    }

    public static TypedProps<CreatureActor> props(SequentialId id, ActorRef collisionDetector, Point position,
                                                  Point worldBoundaries, LearningSettings learningSettings) {
        return new TypedProps<>(Creature.class,
                (Creator<CreatureActor>) () -> new CreatureActor(id, collisionDetector, position, worldBoundaries, learningSettings)
        );
    }


    private final Logger logger = Logger.getLogger(CreatureActor.class.getName());

    private final Point worldBoundaries;

    private final EntityManager em;

    private SequentialId id;

    private Map<Class, Pair<SequentialId, ActorRef>> components;

    private EmotionalSystem emotionalSystem;

    private OperantConditioning operantConditioning;

    private ExpectancyPredictor expectancy;

    private MemorySystem memory;

    private ActorRef consolidator;

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

    // null means "inherit global settings from SimulationSettingsExtension"
    private final LearningSettings learningSettings;

    public CreatureActor(SequentialId id, ActorRef collisionDetector, Point position, Point worldBoundaries,
                         LearningSettings learningSettings) {
        this.id = id;
        this.collisionDetector = collisionDetector;
        this.position = position;
        this.worldBoundaries = worldBoundaries;
        this.learningSettings = learningSettings;
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

        final MetricsExtension.Impl metricsExt = MetricsExtension.of(context.system());

        emotionalSystem = TypedActor.get(TypedActor.context())
                .typedActorOf(new TypedProps<>(EmotionalSystem.class, EmotionalSystemActor::new), "emotionalSystem");

        operantConditioning = TypedActor.get(TypedActor.context())
                .typedActorOf(new TypedProps<>(OperantConditioning.class, OperantConditioningActor::new),
                        "operantConditioning");

        memory = TypedActor.get(TypedActor.context())
                .typedActorOf(new TypedProps<>(MemorySystem.class,
                        (Creator<MemorySystemActor>) () -> new MemorySystemActor(id, metricsExt)), "memorySystem");

        // Resolve effective settings: per-creature override if provided, else global.
        SimulationSettingsExtension.Impl ext = SimulationSettingsExtension.of(context.system());
        final LearningSettings effective = (learningSettings != null) ? learningSettings : ext.learningSettings();
        // Register under this creature's key so components can find it via learningSettings(id.key).
        ext.configure(id.key, effective);

        // Expectancy predictor (TypedActor facade — serialises the single writer, Valuation).
        // JEPA mode: JepaExpectancyPredictor reads the WorldModelFilter's per-cycle prediction cache.
        // The filter is created lazily in FullAppraisal.preStart(); the AtomicReference bridges
        // the timing gap — it is null until preStart() fires, causing expected() to return 0.0.
        final ExpectancyMode expMode = effective.getExpectancyMode();
        final AtomicReference<WorldModelFilter> wmFilterRef =
                (expMode == ExpectancyMode.JEPA) ? new AtomicReference<>() : null;
        final Creator<ExpectancyPredictor> expectancyCreator =
                (expMode == ExpectancyMode.JEPA)
                        ? () -> new JepaExpectancyPredictor(wmFilterRef)
                        : () -> ExpectancyPredictors.forMode(expMode);
        expectancy = TypedActor.get(TypedActor.context())
                .typedActorOf(new TypedProps<>(ExpectancyPredictor.class, expectancyCreator),
                        "expectancy");

        if (effective.isConsolidationEnabled()) {
            boolean jepaMode = effective.isFilterEnabled(ActionSelectionType.WORLD_MODEL);
            if (jepaMode) {
                consolidator = context.actorOf(
                        Props.create(MemoryConsolidator.class, () -> new MemoryConsolidator(id.key))
                                .withDispatcher("wm-dispatcher"),
                        "memoryConsolidator");
            } else {
                consolidator = context.actorOf(
                        Props.create(MemoryTraceConsolidator.class, () -> new MemoryTraceConsolidator(id.key))
                                .withDispatcher("wm-dispatcher"),
                        "memoryConsolidator");
            }
        } else {
            consolidator = context.system().deadLetters();
        }

        //bdActor = context.system().actorOf(Props.create(BDActor.class, em)
        //        .withDispatcher("bd-dispatcher"), "db");

        final MLServiceExtension.Impl mlExt = MLServiceExtension.of(context.system());

        SequentialId componentId = id;
        for (Map.Entry<Class<?>, Function<SequentialId, CreatureComponent>> entry : componentFactories(effective, mlExt, wmFilterRef).entrySet()) {
            componentId = componentId.next();
            final SequentialId cid = componentId;
            final Class<?> componentType = entry.getKey();
            final Function<SequentialId, CreatureComponent> factory = entry.getValue();
            Creator<ComponentActor> creator = () -> new ComponentActor(factory.apply(cid));
            ActorRef component = context.actorOf(
                    Props.create(ComponentActor.class, creator).withDispatcher("component-dispatcher"),
                    componentType.getSimpleName().toLowerCase());
            components.put(componentType, new Pair<>(cid, component));
        }

        final ActorRef partial = components.get(PartialAppraisal.class).second;

        clock = TypedActor.context().system().scheduler()
                .schedule(Duration.apply(5, TimeUnit.SECONDS),
                        Duration.apply(1000, TimeUnit.MILLISECONDS), () -> {
                            logger.info("Clocking");
                            partial.tell("", ActorRef.noSender());
                        }, TypedActor.context().dispatcher());

        collisionDetector.tell(getPositioningAttr(), ActorRef.noSender());
    }

    private LinkedHashMap<Class<?>, Function<SequentialId, CreatureComponent>> componentFactories(
            LearningSettings effective, MLServiceExtension.Impl mlExt,
            AtomicReference<WorldModelFilter> wmFilterRef) {
        LinkedHashMap<Class<?>, Function<SequentialId, CreatureComponent>> factories = new LinkedHashMap<>();
        factories.put(Eye.class,                   Eye::new);
        factories.put(Body.class,                  Body::new);
        factories.put(Mouth.class,                 Mouth::new);
        factories.put(Nose.class,                  Nose::new);
        factories.put(SensoryCortex.class,         SensoryCortex::new);
        factories.put(EffectorCortex.class,        EffectorCortex::new);
        factories.put(PartialAppraisal.class,      id -> new PartialAppraisal(id, effective));
        factories.put(FullAppraisal.class,         id -> new FullAppraisal(id, effective, mlExt, wmFilterRef));
        factories.put(HomeostaticRegulation.class, id -> new HomeostaticRegulation(id, effective));
        factories.put(Valuation.class,             id -> new Valuation(id, effective));
        factories.put(NeuromodulatorSystem.class,  NeuromodulatorSystem::new);
        factories.put(EndocrineSystem.class,       EndocrineSystem::new);
        return factories;
    }

    public void kill() {
        clock.cancel();
        state.setDeadTime(System.currentTimeMillis());

        for (Pair<SequentialId, ActorRef> p : components.values()) {
            TypedActor.context().stop(p.second);
        }
        TypedActor.context().stop(consolidator);
        MLServiceExtension.of(TypedActor.context().system()).releaseAdapter(id.key);
        SimulationSettingsExtension.of(TypedActor.context().system()).releaseCreatureSettings(id.key);

        em.getTransaction().begin();
        em.persist(state);
        em.getTransaction().commit();
        em.close();

        logger.info("Sending remove order to holder");
        holderActorRef().tell(id, TypedActor.context().self());
        logger.info("Creature " + id + " killed");
    }

    private ActorRef holderActorRef() {
        return TypedActor.context().parent();
    }

    @Override
    public ComponentRef holder() {
        return new AkkaComponentRef(holderActorRef());
    }

    private CreaturePositioningAttr getPositioningAttr() {
        return new CreaturePositioningAttr(id,
                componentId(Body.class), componentId(Eye.class), componentId(Nose.class), componentId(Mouth.class),
                TypedActor.context().self(),
                componentRef(Body.class), componentRef(Eye.class), componentRef(Nose.class), componentRef(Mouth.class),
                position, visionFieldPosition, visionFieldOpening, olfactoryFieldRadius, false, false);
    }

    private SequentialId componentId(Class componentClass) {
        return components.get(componentClass).first;
    }

    private ActorRef componentRef(Class componentClass) {
        return components.get(componentClass).second;
    }

    private void updatePositioningAttribute() {
        ActorRef self = TypedActor.context().self();
        //logger.info("Updating creature positioning attr of " + self + " to " + collisionDetector);
        collisionDetector.tell(getPositioningAttr(), self);
    }

    private ComponentRef refOf(Class componentClass) {
        return new AkkaComponentRef(components.get(componentClass).second);
    }

    public ComponentRef eye()             { return refOf(Eye.class); }
    public ComponentRef body()            { return refOf(Body.class); }
    public ComponentRef mouth()           { return refOf(Mouth.class); }
    public ComponentRef nose()            { return refOf(Nose.class); }
    public ComponentRef sensoryCortex()   { return refOf(SensoryCortex.class); }
    public ComponentRef effectorCortex()  { return refOf(EffectorCortex.class); }
    public ComponentRef partialAppraisal(){ return refOf(PartialAppraisal.class); }
    public ComponentRef fullAppraisal()   { return refOf(FullAppraisal.class); }
    public ComponentRef homeostatic()     { return refOf(HomeostaticRegulation.class); }
    public ComponentRef valuation()       { return refOf(Valuation.class); }
    public ComponentRef neuromodulators() { return refOf(NeuromodulatorSystem.class); }
    public ComponentRef endocrine()       { return refOf(EndocrineSystem.class); }

    @Override
    public EmotionalSystem emotions() {
        return emotionalSystem;
    }

    @Override
    public OperantConditioning operantConditioning() {
        return operantConditioning;
    }

    @Override
    public ExpectancyPredictor expectancy() {
        return expectancy;
    }

    @Override
    public MemorySystem memory() {
        return memory;
    }

    @Override
    public ComponentRef memoryConsolidator() {
        return new AkkaComponentRef(consolidator);
    }

    @Override
    public ComponentRef bd() {
        return new AkkaComponentRef(bdActor != null ? bdActor : TypedActor.context().system().deadLetters());
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
