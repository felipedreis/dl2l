package br.cefetmg.lsi.l2l.creature;

import akka.actor.ActorRef;
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
     * It shall return an ActorRef to the current holder that represents a world's portion in a cluster node.
     * When a creature component
     * @return  ActorRef parent
     */
    ActorRef holder();

    /// Somatic system components
    ActorRef eye();
    ActorRef body();
    ActorRef mouth();
    ActorRef nose();

    /// Sensory-motor cortex components
    ActorRef sensoryCortex();
    ActorRef effectorCortex();

    /// Emotional-cognitive components components
    ActorRef partialAppraisal();
    ActorRef fullAppraisal();
    ActorRef homeostatic();
    ActorRef valuation();

    EmotionalSystem emotions();

    OperantConditioning operantConditioning();
    MemorySystem memory();

    // Persistence component
    ActorRef bd();


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
