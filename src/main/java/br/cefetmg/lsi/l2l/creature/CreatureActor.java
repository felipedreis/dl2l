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
import br.cefetmg.lsi.l2l.creature.bd.PersistenceExtension;
import br.cefetmg.lsi.l2l.creature.bd.PersistenceState;
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

    private SequentialId id;

    private Map<Class, Pair<SequentialId, ActorRef>> components;

    private EmotionalSystem emotionalSystem;

    private OperantConditioning operantConditioning;

    private ExpectancyPredictor expectancy;

    private MemorySystem memory;

    private ActorRef consolidator;

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
    }

    public void init() {
        ActorContext context = TypedActor.context();
        components = new HashMap<>();

        state = new CreatureState(id);
        state.setBornTime(System.currentTimeMillis());

        // Routes through the single per-JVM BDActor (see PersistenceExtension) instead of
        // opening its own connection - avoids a repeat of the "~70-100 separate connections"
        // incident documented there. Async like every other creature write; Holder's
        // pre-extraction Flush (handleRemoveObject/handleFinish) guarantees this lands
        // before data is read back out.
        bd().tell(new PersistenceState[]{state});

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

        // Issue #79 Phase B: captured while running ON the actor's own thread (init() is
        // itself invoked through the typed-actor proxy - see Holder.java), so this proxy is
        // safe to invoke from the scheduler's thread below; calls through it are dispatched
        // onto the actor's mailbox like any other message, not executed directly on the
        // scheduler thread. See Creature.tick()'s javadoc.
        final Creature self = (Creature) TypedActor.self();

        clock = TypedActor.context().system().scheduler()
                .schedule(Duration.apply(5, TimeUnit.SECONDS),
                        Duration.apply(1000 / Constants.TARGET_CYCLE_HZ, TimeUnit.MILLISECONDS), () -> {
                            logger.fine("Clocking");
                            self.tick();
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

        // Same UUID as the birth write above - no upsert semantics (see ArrowIpcBackend's
        // javadoc): this lands as a second, duplicate creature_state row rather than
        // overwriting deadtime in place. Deduped downstream at extraction time, not here -
        // see docs/plans/arrow-ipc-write-path.md PR 2's creatures birth/death dedup.
        bd().tell(new PersistenceState[]{state});

        logger.info("Sending remove order to holder");
        holderActorRef().tell(id, TypedActor.context().self());
        logger.info("Creature " + id + " killed");
    }

    /**
     * Issue #79 Phase B: called once per wall-clock tick by the {@code clock} scheduler
     * (see {@code init()}). Two independent things happen here, exactly as they did before
     * Phase B, just now both gated to the same tick instead of one being 1Hz-fixed and the
     * other unbounded:
     *
     * <p>1. The direct heartbeat to {@code PartialAppraisal} guarantees a cognitive cycle
     * fires this tick even with zero perception. Without it, {@code PartialAppraisal.onReceive}
     * only fires when {@code SensoryCortex} has a stimulus to forward - which never happens
     * if nothing is within sensory range - so a creature alone in empty space would never
     * metabolize, check death, or act. This is the same unconditional send the pre-Phase-B
     * 1Hz keep-alive scheduler made.
     *
     * <p>2. {@link #updatePositioningAttribute()} broadcasts this tick's position/perceptual
     * fields to the collision detector, which - asynchronously, and only if something is
     * actually nearby - triggers its own separate perception-driven cycle(s) on
     * {@code PartialAppraisal}. This was always decoupled in timing from the heartbeat (the
     * collision-detector round trip can even cross nodes); Phase B only changes *how often*
     * a new broadcast is triggered (once per tick, not once per movement), not this
     * asynchrony. See this method's declaration on {@link Creature} for why it's routed
     * through the typed-actor proxy.
     */
    @Override
    public void tick() {
        componentRef(PartialAppraisal.class).tell("", ActorRef.noSender());
        updatePositioningAttribute();
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
        return new AkkaComponentRef(PersistenceExtension.of(TypedActor.context().system()).bdActor());
    }

    public Point getPosition() {
        return position;
    }

    // Issue #79 Phase B: setPosition/setVisionFieldOpening/setVisionFieldPosition/
    // setOlfactoryFieldRadius below update state only - they used to each call
    // updatePositioningAttribute() immediately, which is what made the perception cascade
    // self-perpetuating (every movement/field change re-triggered a full perception round
    // right away, unbounded by anything but dispatcher speed). tick() (driven by the
    // wall-clock `clock` scheduler in init()) is now the sole place that calls
    // updatePositioningAttribute(), so any number of these setters firing within one cycle
    // coalesce into the one positioning send the next tick makes - see
    // docs/plans/issue-79-decouple-biological-clock.md's Phase B section.
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
    }

    public void setVisionFieldOpening(double opening) {
        this.visionFieldOpening = opening;
    }

    public double getVisionFieldOpening() {
        return visionFieldOpening;
    }

    public void setVisionFieldPosition(double arc) {
        this.visionFieldPosition = arc;
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
    }

    public double getOlfactoryFieldRadius() {
        return olfactoryFieldRadius;
    }

}
