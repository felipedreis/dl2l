package br.cefetmg.lsi.l2l.creature;

import br.cefetmg.lsi.l2l.common.Point;
import br.cefetmg.lsi.l2l.creature.components.EmotionalSystem;
import br.cefetmg.lsi.l2l.creature.conditioning.OperantConditioning;
import br.cefetmg.lsi.l2l.creature.conditioning.expectancy.ExpectancyPredictor;
import br.cefetmg.lsi.l2l.creature.memory.MemorySystem;

/**
 * Created by felipe on 02/01/17.
 */
public interface Creature {
    void init();

    /**
     * It shall return a {@link ComponentRef} to the current holder that represents a
     * world's portion in a cluster node. When a creature component needs to send a
     * message to the outside world (e.g. a {@code DestructiveStimulus}) it routes it
     * through here.
     */
    ComponentRef holder();

    /// Somatic system components
    ComponentRef eye();
    ComponentRef body();
    ComponentRef mouth();
    ComponentRef nose();

    /// Sensory-motor cortex components
    ComponentRef sensoryCortex();
    ComponentRef effectorCortex();

    /// Emotional-cognitive components components
    ComponentRef partialAppraisal();
    ComponentRef fullAppraisal();
    ComponentRef homeostatic();
    ComponentRef valuation();

    /// Neuromodulator pool (dopamine / serotonin / orexin) — untyped, message-driven
    ComponentRef neuromodulators();

    /// Endocrine system (cortisol / HPA axis) — untyped, message-driven
    ComponentRef endocrine();

    EmotionalSystem emotions();

    OperantConditioning operantConditioning();
    MemorySystem memory();

    /// Symbolic reward-expectancy predictor (baseline for the dopaminergic RPE)
    ExpectancyPredictor expectancy();

    // Sleep-gated adapter consolidation
    ComponentRef memoryConsolidator();

    // Persistence component
    ComponentRef bd();


    void kill();

    /**
     * Issue #79 Phase B: the creature's own wall-clock pacemaker (see
     * {@code CreatureActor}'s {@code clock} field) calls this once per tick, at
     * {@link br.cefetmg.lsi.l2l.common.Constants#TARGET_CYCLE_HZ}. Guarantees at least one
     * cognitive cycle fires this tick (a direct heartbeat to PartialAppraisal - needed
     * because perception alone cannot guarantee this: SensoryCortex only forwards to
     * PartialAppraisal when it has a stimulus, so a creature with nothing nearby would
     * otherwise never cognize) and broadcasts this tick's position for perception, which
     * may asynchronously produce further, separate cycles - see the implementation's
     * javadoc for the full breakdown. Routed through this interface (rather than the
     * scheduler calling a private method directly) so the call is dispatched through the
     * TypedActor proxy onto the actor's own thread, same as any other message.
     */
    void tick();

    /// TODO Memory systems


    /// CreatureSetting visible attributes
    void setAlive(boolean alive);
    boolean isAlive();

    void setPosition(Point point);
    Point getPosition();

    void setVisionFieldOpening(double opening);
    double getVisionFieldOpening();

    void setVisionFieldPosition(double arc);
    double getVisionFieldPosition();

    void setOlfactoryFieldRadius(double radius);
    double getOlfactoryFieldRadius();

}
