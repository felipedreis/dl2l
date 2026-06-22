package br.cefetmg.lsi.l2l.web;

import akka.NotUsed;
import akka.japi.Pair;
import akka.stream.Materializer;
import akka.stream.OverflowStrategy;
import akka.stream.javadsl.*;
import br.cefetmg.lsi.l2l.common.SequentialId;
import br.cefetmg.lsi.l2l.physics.CreatureGeometry;
import br.cefetmg.lsi.l2l.physics.ObjectGeometry;

import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;

public class GeometrySourceProvider {
    private final SourceQueueWithComplete<CreatureGeometry> creatureQueue;
    private final SourceQueueWithComplete<ObjectGeometry> objectQueue;
    private final SourceQueueWithComplete<SequentialId> removalQueue;

    private final Source<CreatureGeometry, NotUsed> creatureSource;
    private final Source<ObjectGeometry, NotUsed> objectSource;
    private final Source<SequentialId, NotUsed> removalSource;

    private final ConcurrentHashMap<SequentialId, ObjectGeometry> objectSnapshot = new ConcurrentHashMap<>();

    public GeometrySourceProvider(Materializer materializer) {
        Pair<SourceQueueWithComplete<CreatureGeometry>, Source<CreatureGeometry, NotUsed>> creaturePair =
            Source.<CreatureGeometry>queue(1000, OverflowStrategy.dropHead()).preMaterialize(materializer);

        Pair<SourceQueueWithComplete<ObjectGeometry>, Source<ObjectGeometry, NotUsed>> objectPair =
            Source.<ObjectGeometry>queue(1000, OverflowStrategy.dropHead()).preMaterialize(materializer);

        Pair<SourceQueueWithComplete<SequentialId>, Source<SequentialId, NotUsed>> removalPair =
            Source.<SequentialId>queue(1000, OverflowStrategy.dropHead()).preMaterialize(materializer);

        this.creatureQueue = creaturePair.first();
        this.objectQueue = objectPair.first();
        this.removalQueue = removalPair.first();
        this.creatureSource = creaturePair.second();
        this.objectSource = objectPair.second();
        this.removalSource = removalPair.second();
    }

    public void updateCreature(CreatureGeometry geometry) {
        creatureQueue.offer(geometry);
    }

    public void updateObject(ObjectGeometry geometry) {
        objectSnapshot.put(geometry.id, geometry);
        objectQueue.offer(geometry);
    }

    public void removeObject(SequentialId id) {
        objectSnapshot.remove(id);
        removalQueue.offer(id);
    }

    public Collection<ObjectGeometry> getObjectSnapshot() {
        return Collections.unmodifiableCollection(objectSnapshot.values());
    }

    public Source<CreatureGeometry, NotUsed> getCreatureSource() {
        return creatureSource;
    }

    public Source<ObjectGeometry, NotUsed> getObjectSource() {
        return objectSource;
    }

    public Source<SequentialId, NotUsed> getRemovalSource() {
        return removalSource;
    }
}