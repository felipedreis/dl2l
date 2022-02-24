package br.cefetmg.lsi.l2l.world;

import akka.actor.ActorRef;
import akka.actor.Props;
import br.cefetmg.lsi.l2l.common.Point;
import br.cefetmg.lsi.l2l.common.SequentialId;
import br.cefetmg.lsi.l2l.stimuli.DestructiveStimulus;
import br.cefetmg.lsi.l2l.stimuli.EnergeticStimulus;

/**
 * Created by felipe on 06/01/17.
 */
public class Fruit extends WorldObject {

    private FruitType type;

    public static Props props(SequentialId id, WorldObjectType type, Point position, ActorRef collisionDetector) {
        return Props.create(Fruit.class, id, type, position, collisionDetector);
    }

    public Fruit(SequentialId id, WorldObjectType type, Point position, ActorRef collisionDetector) {
        super(id, type, position, collisionDetector);

        this.type = (FruitType) type;
    }

    @Override
    public void onReceive(Object message) {
        if (message instanceof DestructiveStimulus) {
            logger.info("Fruit " +  id + " was eaten");

            sender().tell(new EnergeticStimulus(id, nextStimulusId(), type.caloricValue, type), self());
            context().parent().tell(id, self());

        } else
            unhandled(message);

    }
}
