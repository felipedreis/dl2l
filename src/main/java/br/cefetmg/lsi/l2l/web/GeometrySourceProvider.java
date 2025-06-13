package br.cefetmg.lsi.l2l.gui;

import akka.NotUsed;
import akka.stream.javadsl.*;
import br.cefetmg.lsi.l2l.physics.CreatureGeometry;
import br.cefetmg.lsi.l2l.physics.ObjectGeometry;

public class GeometrySourceProvider {
    private final SourceQueueWithComplete<CreatureGeometry> creatureQueue;
    private final SourceQueueWithComplete<ObjectGeometry> objectQueue;
    private final Source<CreatureGeometry, NotUsed> creatureSource;
    private final Source<ObjectGeometry, NotUsed> objectSource;

    public GeometrySourceProvider() {
        Pair<SourceQueueWithComplete<CreatureGeometry>, Source<CreatureGeometry, NotUsed>> creaturePair = 
            Source.<CreatureGeometry>queue(1000, OverflowStrategy.dropHead()).preMaterialize(ActorMaterializer.create());
        
        Pair<SourceQueueWithComplete<ObjectGeometry>, Source<ObjectGeometry, NotUsed>> objectPair = 
            Source.<ObjectGeometry>queue(1000, OverflowStrategy.dropHead()).preMaterialize(ActorMaterializer.create());

        this.creatureQueue = creaturePair.first();
        this.objectQueue = objectPair.first();
        this.creatureSource = creaturePair.second();
        this.objectSource = objectPair.second();
    }

    public void updateCreature(CreatureGeometry geometry) {
        creatureQueue.offer(geometry);
    }

    public void updateObject(ObjectGeometry geometry) {
        objectQueue.offer(geometry);
    }

    public Source<CreatureGeometry, NotUsed> getCreatureSource() {
        return creatureSource;
    }

    public Source<ObjectGeometry, NotUsed> getObjectSource() {
        return objectSource;
    }
}