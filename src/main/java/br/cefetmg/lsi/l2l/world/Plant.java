package br.cefetmg.lsi.l2l.world;

import akka.actor.ActorRef;
import akka.actor.Props;
import br.cefetmg.lsi.l2l.common.Point;
import br.cefetmg.lsi.l2l.common.SequentialId;
import br.cefetmg.lsi.l2l.creature.common.ActionType;
import br.cefetmg.lsi.l2l.stimuli.DestructiveStimulus;
import br.cefetmg.lsi.l2l.stimuli.NociceptiveStimulus;

public class Plant extends WorldObject {

    private PlantType type;

    public static Props props(SequentialId id, WorldObjectType type, Point position, ActorRef collisionDetector) {
        return Props.create(Plant.class, id, type, position, collisionDetector);
    }

    public Plant(SequentialId id, WorldObjectType type, Point position, ActorRef collisionDetector) {
        super(id, type, position, collisionDetector);
        this.type = (PlantType) type;
    }

    @Override
    public void onReceive(Object message) {
        if (message instanceof DestructiveStimulus) {
            logger.info("Plant " + id + " was touched by creature — sending pain");
            sender().tell(
                new NociceptiveStimulus(id, nextStimulusId(), type.activePain, ActionType.EAT, type),
                self());
            // Plant does NOT notify its parent: it is permanent and never removed.
        } else {
            unhandled(message);
        }
    }
}
