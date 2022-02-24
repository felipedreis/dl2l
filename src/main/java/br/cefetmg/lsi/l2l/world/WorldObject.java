package br.cefetmg.lsi.l2l.world;

import akka.actor.ActorRef;
import akka.actor.UntypedActor;
import br.cefetmg.lsi.l2l.common.Point;
import br.cefetmg.lsi.l2l.common.SequentialId;
import br.cefetmg.lsi.l2l.physics.ObjectGeometry;
import br.cefetmg.lsi.l2l.physics.WorldObjectPositioningAttr;

import java.util.logging.Logger;

/**
 * Created by felipe on 06/01/17.
 */
public abstract class WorldObject extends UntypedActor {

    protected WorldObjectType type;

    protected SequentialId id;

    private SequentialId stimulusId;

    ActorRef collisionDetector;

    Point position;

    final Logger logger;

    public WorldObject(SequentialId id, WorldObjectType type, Point position, ActorRef collisionDetector) {
        this.id = id;
        this.type = type;
        this.position = position;
        this.collisionDetector = collisionDetector;
        logger = Logger.getLogger(this.getClass().getName());
        stimulusId = new SequentialId(id.key);
    }

    @Override
    public void preStart()  {
        collisionDetector.tell(new WorldObjectPositioningAttr(id, position, type), self());
    }

    @Override
    public void postStop()  {
        collisionDetector.tell(id, self());
    }

    SequentialId nextStimulusId() {
        stimulusId = stimulusId.next();

        return stimulusId;
    }
}
