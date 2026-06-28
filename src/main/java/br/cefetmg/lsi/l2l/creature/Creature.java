package br.cefetmg.lsi.l2l.creature;

import br.cefetmg.lsi.l2l.common.Point;
import br.cefetmg.lsi.l2l.creature.components.EmotionalSystem;
import br.cefetmg.lsi.l2l.creature.conditioning.OperantConditioning;
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

    EmotionalSystem emotions();

    OperantConditioning operantConditioning();
    MemorySystem memory();

    // Sleep-gated adapter consolidation
    ComponentRef memoryConsolidator();

    // Persistence component
    ComponentRef bd();


    void kill();

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
