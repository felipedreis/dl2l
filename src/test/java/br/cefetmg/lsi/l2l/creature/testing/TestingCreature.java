package br.cefetmg.lsi.l2l.creature.testing;

import br.cefetmg.lsi.l2l.cluster.settings.LearningSettings;
import br.cefetmg.lsi.l2l.common.Constants;
import br.cefetmg.lsi.l2l.common.Point;
import br.cefetmg.lsi.l2l.common.SequentialId;
import br.cefetmg.lsi.l2l.creature.ComponentRef;
import br.cefetmg.lsi.l2l.creature.Creature;
import br.cefetmg.lsi.l2l.creature.bd.NoOpPersister;
import br.cefetmg.lsi.l2l.creature.components.*;
import br.cefetmg.lsi.l2l.creature.conditioning.OperantConditioning;
import br.cefetmg.lsi.l2l.creature.conditioning.OperantConditioningActor;
import br.cefetmg.lsi.l2l.creature.memory.MemorySystem;
import br.cefetmg.lsi.l2l.creature.memory.MemorySystemActor;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Single-threaded, no-{@code ActorSystem} implementation of {@link Creature}. Wires
 * every real {@link CreatureComponent} class in-process. Each component owns a
 * {@link BatchingDispatcher} that mirrors {@code ComponentMessageQueue}; outbound
 * references are wrapped in {@link RecordingComponentRef} so tests can observe the
 * stimulus chain. External collaborators (holder, memoryConsolidator, bd) are
 * {@link ExternalSink}s that capture outbound messages.
 *
 * Created and driven through {@link TestingHarness}.
 */
public final class TestingCreature implements Creature {

    private final SequentialId id;
    private final Point worldBoundaries;
    private final LearningSettings learningSettings;

    private final EmotionalSystem emotionalSystem = new EmotionalSystemActor();
    private final OperantConditioning operantConditioning = new OperantConditioningActor();
    private final MemorySystem memory = new MemorySystemActor();

    private Point position;
    private boolean alive;
    private double visionFieldOpening;
    private double visionFieldPosition;
    private double olfactoryFieldRadius;

    // External world stubs
    private final ExternalSink holder = new ExternalSink("holder");
    private final ExternalSink memoryConsolidator = new ExternalSink("memoryConsolidator");
    private final ExternalSink bd = new ExternalSink("bd");

    // One BatchingDispatcher per real CreatureComponent
    private final Map<Class<?>, BatchingDispatcher> dispatchers = new LinkedHashMap<>();
    private final Map<Class<?>, RecordingComponentRef> refs = new LinkedHashMap<>();
    private final Map<Class<?>, CreatureComponent> components = new LinkedHashMap<>();

    private boolean initialised = false;

    public TestingCreature(SequentialId id, Point position, Point worldBoundaries,
                           LearningSettings learningSettings) {
        this.id = id;
        this.position = position;
        this.worldBoundaries = worldBoundaries;
        this.learningSettings = learningSettings;
    }

    @Override
    public void init() {
        if (initialised) return;
        initialised = true;
        alive = true;
        visionFieldOpening = Constants.MIN_VISION_FIELD_OPENING;
        olfactoryFieldRadius = Constants.MIN_OLFACTORY_FIELD_RADIUS;

        // Instantiate every component and its dispatcher.
        SequentialId cid = id;
        register(cid = cid.next(), new Eye(cid));
        register(cid = cid.next(), new Body(cid));
        register(cid = cid.next(), new Mouth(cid));
        register(cid = cid.next(), new Nose(cid));
        register(cid = cid.next(), new SensoryCortex(cid));
        register(cid = cid.next(), new EffectorCortex(cid));
        register(cid = cid.next(), new PartialAppraisal(cid, learningSettings));
        // FullAppraisal: pass null for MLServiceExtension.Impl so WORLD_MODEL is skipped.
        register(cid = cid.next(), new FullAppraisal(cid, learningSettings, null));
        register(cid = cid.next(), new HomeostaticRegulation(cid, learningSettings));
        register(cid = cid.next(), new Valuation(cid));

        // Wire each component to the Creature + persister + self-ref.
        components.forEach((cls, component) -> {
            RecordingComponentRef selfRef = refs.get(cls);
            component.init(this, new NoOpPersister(), selfRef);
        });
    }

    private void register(SequentialId componentId, CreatureComponent component) {
        Class<?> type = component.getClass();
        BatchingDispatcher dispatcher = new BatchingDispatcher(component);
        RecordingComponentRef ref = new RecordingComponentRef(type.getSimpleName(), dispatcher);
        dispatchers.put(type, dispatcher);
        refs.put(type, ref);
        components.put(type, component);
    }

    BatchingDispatcher dispatcherOf(Class<? extends CreatureComponent> type) {
        return dispatchers.get(type);
    }

    RecordingComponentRef recorderOf(Class<? extends CreatureComponent> type) {
        return refs.get(type);
    }

    Iterable<BatchingDispatcher> allDispatchers() {
        return dispatchers.values();
    }

    public ExternalSink holderSink()             { return holder; }
    public ExternalSink memoryConsolidatorSink() { return memoryConsolidator; }
    public ExternalSink bdSink()                 { return bd; }

    public SequentialId id() { return id; }

    // --- Creature interface ---

    @Override
    public ComponentRef holder() { return holder; }

    @Override
    public ComponentRef eye()             { return refs.get(Eye.class); }
    @Override
    public ComponentRef body()            { return refs.get(Body.class); }
    @Override
    public ComponentRef mouth()           { return refs.get(Mouth.class); }
    @Override
    public ComponentRef nose()            { return refs.get(Nose.class); }
    @Override
    public ComponentRef sensoryCortex()   { return refs.get(SensoryCortex.class); }
    @Override
    public ComponentRef effectorCortex()  { return refs.get(EffectorCortex.class); }
    @Override
    public ComponentRef partialAppraisal(){ return refs.get(PartialAppraisal.class); }
    @Override
    public ComponentRef fullAppraisal()   { return refs.get(FullAppraisal.class); }
    @Override
    public ComponentRef homeostatic()     { return refs.get(HomeostaticRegulation.class); }
    @Override
    public ComponentRef valuation()       { return refs.get(Valuation.class); }

    @Override
    public EmotionalSystem emotions() { return emotionalSystem; }

    @Override
    public OperantConditioning operantConditioning() { return operantConditioning; }

    @Override
    public MemorySystem memory() { return memory; }

    @Override
    public ComponentRef memoryConsolidator() { return memoryConsolidator; }

    @Override
    public ComponentRef bd() { return bd; }

    @Override
    public void kill() {
        alive = false;
        holder.tell(id);
    }

    @Override
    public void setAlive(boolean alive) {
        this.alive = alive;
        if (!alive) kill();
    }

    @Override
    public boolean isAlive() { return alive; }

    @Override
    public void setPosition(Point point) {
        double x, y;
        if (point.x > worldBoundaries.x)      x = 0;
        else if (point.x < 0)                  x = worldBoundaries.x;
        else                                   x = point.x;
        if (point.y > worldBoundaries.y)      y = 0;
        else if (point.y < 0)                  y = worldBoundaries.y;
        else                                   y = point.y;
        this.position = new Point(x, y);
    }

    @Override
    public Point getPosition() { return position; }

    @Override
    public void setVisionFieldOpening(double opening) { this.visionFieldOpening = opening; }
    @Override
    public double getVisionFieldOpening() { return visionFieldOpening; }

    @Override
    public void setVisionFieldPosition(double arc) { this.visionFieldPosition = arc; }
    @Override
    public double getVisionFieldPosition() { return visionFieldPosition; }

    @Override
    public void setOlfactoryFieldRadius(double radius) { this.olfactoryFieldRadius = radius; }
    @Override
    public double getOlfactoryFieldRadius() { return olfactoryFieldRadius; }
}
