package br.cefetmg.lsi.l2l.world;

import akka.actor.ActorRef;
import akka.actor.Props;
import br.cefetmg.lsi.l2l.common.Point;
import br.cefetmg.lsi.l2l.common.SequentialId;
import br.cefetmg.lsi.l2l.creature.common.ActionType;
import br.cefetmg.lsi.l2l.stimuli.AnalgesicStimulus;
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
            if (type.activePain > 0) {
                logger.info("Plant " + id + " (" + type.name() + ") caused pain on eat");
                sender().tell(
                    new NociceptiveStimulus(id, nextStimulusId(), type.activePain, ActionType.EAT, type),
                    self());
                // Painful plants (cactus) are permanent — do not self-destruct.
            } else if (type.healAmount > 0) {
                logger.info("Plant " + id + " (" + type.name() + ") was eaten for healing");
                sender().tell(
                    new AnalgesicStimulus(id, nextStimulusId(), type.healAmount, ActionType.EAT, type),
                    self());
                // Healing plants are consumed — notify parent to remove.
                context().parent().tell(id, self());
            }
        } else {
            unhandled(message);
        }
    }
}
