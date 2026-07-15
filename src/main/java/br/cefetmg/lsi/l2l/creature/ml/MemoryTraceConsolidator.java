package br.cefetmg.lsi.l2l.creature.ml;

import akka.actor.TypedActor;
import akka.actor.TypedProps;
import akka.actor.UntypedActor;
import br.cefetmg.lsi.l2l.common.Constants;
import br.cefetmg.lsi.l2l.common.SequentialId;
import br.cefetmg.lsi.l2l.creature.Creature;
import br.cefetmg.lsi.l2l.creature.CreatureActor;
import br.cefetmg.lsi.l2l.creature.bd.MemoryTraceStat;
import br.cefetmg.lsi.l2l.creature.common.ActionType;
import br.cefetmg.lsi.l2l.creature.memory.Engram;
import br.cefetmg.lsi.l2l.creature.memory.MemorySystem;
import br.cefetmg.lsi.l2l.world.WorldObjectType;

import javax.persistence.EntityManager;
import javax.persistence.Persistence;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Logger;

/**
 * Mapa-style sleep consolidation actor.
 *
 * On sleep onset, groups recent engrams by (ActionType, WorldObjectType) and
 * creates one "consolidated" engram per group whose mean(-emotionDelta) exceeds
 * MEMORY_CONSOLIDATION_THRESHOLD. Consolidated engrams carry eligibility=1.0 so
 * the MemoryFilter assigns them maximum weight in subsequent waking cycles.
 *
 * No neural network involved — this is a purely symbolic memory reinforcement step
 * that mirrors Suelen Mapa's long-term memory consolidation (2009 dissertation).
 *
 * Parallel implementation to MemoryConsolidator (JEPA adapter training).
 * CreatureActor selects between the two at construction time based on LearningSettings.
 */
public class MemoryTraceConsolidator extends UntypedActor {

    private static final Logger logger = Logger.getLogger(MemoryTraceConsolidator.class.getName());

    private final long creatureKey;
    private MemorySystem memory;
    private final EntityManager em = Persistence.createEntityManagerFactory("L2LPU",
            br.cefetmg.lsi.l2l.creature.bd.JpaPersister.jdbcUrlOverride()).createEntityManager();

    public MemoryTraceConsolidator(long creatureKey) {
        this.creatureKey = creatureKey;
    }

    @Override
    public void preStart() throws Exception {
        super.preStart();
        Creature creature = TypedActor.get(context().system())
                .typedActorOf(new TypedProps<>(Creature.class, CreatureActor.class), context().parent());
        memory = creature.memory();
        logger.info("MemoryTraceConsolidator[" + creatureKey + "]: started (Mapa consolidation mode)");
    }

    @Override
    public void postStop() throws Exception {
        super.postStop();
        em.close();
        logger.info("MemoryTraceConsolidator[" + creatureKey + "]: stopped");
    }

    @Override
    public void onReceive(Object message) {
        if (message instanceof SleepStarted s) {
            handleSleepStarted(s);
        } else if (message instanceof WakeUp) {
            // No background thread to abort — nothing to do.
        } else {
            unhandled(message);
        }
    }

    private void handleSleepStarted(SleepStarted msg) {
        List<Engram> engrams = memory.getRecentEngrams(Constants.CONSOLIDATION_WINDOW);
        if (engrams.isEmpty()) {
            logger.info("MemoryTraceConsolidator[" + creatureKey + "]: no engrams, skipping");
            return;
        }

        List<Engram> consolidated = consolidate(engrams, msg.onsetCycle());

        for (Engram e : consolidated) {
            memory.addEngram(e);
        }

        logger.info(String.format(
                "MemoryTraceConsolidator[%d]: examined %d engrams, consolidated %d groups",
                creatureKey, engrams.size(), consolidated.size()));

        persist(new MemoryTraceStat(creatureKey, msg.onsetCycle(), engrams.size(), consolidated.size()));
    }

    private void persist(MemoryTraceStat stat) {
        em.getTransaction().begin();
        em.persist(stat);
        em.getTransaction().commit();
        em.clear();
    }

    /**
     * Core consolidation logic. Package-private for unit testing.
     *
     * Groups engrams by (ActionType, WorldObjectType). For groups whose
     * mean(-emotionDelta) exceeds MEMORY_CONSOLIDATION_THRESHOLD, produces one
     * consolidated Engram with eligibility=1.0 and emotionDelta=group mean.
     */
    static List<Engram> consolidate(List<Engram> engrams, long currentCycle) {
        Map<GroupKey, List<Engram>> groups = new HashMap<>();
        for (Engram e : engrams) {
            WorldObjectType objType = e.perception().objectType.getOrElse(null);
            groups.computeIfAbsent(new GroupKey(e.actionType(), objType), k -> new ArrayList<>()).add(e);
        }

        List<Engram> result = new ArrayList<>();
        for (Map.Entry<GroupKey, List<Engram>> entry : groups.entrySet()) {
            List<Engram> group = entry.getValue();
            double meanDelta = group.stream().mapToDouble(Engram::emotionDelta).average().orElse(0.0);

            // mean(-emotionDelta) > threshold means the action consistently reduced aversive emotion
            if (-meanDelta > Constants.MEMORY_CONSOLIDATION_THRESHOLD) {
                Engram consolidated = new Engram(
                        entry.getKey().actionType(),
                        new SequentialId(0),     // synthetic id — not tied to a real world object
                        null,                    // no emotion snapshot for consolidated engrams
                        group.get(0).perception(), // representative perception (same objectType)
                        currentCycle,
                        meanDelta,
                        1.0,                     // max eligibility — consolidated traces are maximally trusted
                        currentCycle
                );
                result.add(consolidated);
            }
        }
        return result;
    }

    private record GroupKey(ActionType actionType, WorldObjectType objectType) {
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof GroupKey other)) return false;
            return actionType == other.actionType && Objects.equals(objectType, other.objectType);
        }

        @Override
        public int hashCode() {
            return Objects.hash(actionType, objectType);
        }
    }
}
