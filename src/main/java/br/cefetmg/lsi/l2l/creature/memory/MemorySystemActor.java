package br.cefetmg.lsi.l2l.creature.memory;

import br.cefetmg.lsi.l2l.common.Constants;
import br.cefetmg.lsi.l2l.common.SequentialId;
import br.cefetmg.lsi.l2l.metrics.MetricsExtension;

import java.util.*;
import java.util.stream.Collectors;

public class MemorySystemActor implements MemorySystem {

    private static final int MAX_SIZE = 1000;
    private static final int MAX_ENGRAM_SIZE = 1000;
    private static final double LAMBDA = Math.log(2) / Constants.TRACE_DECAY_HALF_LIFE;

    private final ArrayDeque<ShortTermMemory> all = new ArrayDeque<>();
    private final HashMap<SequentialId, List<ShortTermMemory>> byId = new HashMap<>();
    private final BoundedEngramStore engrams = new BoundedEngramStore(Constants.MAX_ENGRAMS_PER_KEY);
    /**
     * Consummatory engrams get their own FIFO so the flood of approach traces cannot evict them.
     * Same cap, but it now buys ~1000 feeding memories instead of ~8 — enough to cover a
     * creature's entire feeding history rather than its last three seconds of it.
     */
    private final BoundedEngramStore consummatoryEngrams =
            new BoundedEngramStore(Constants.MAX_ENGRAMS_PER_KEY);

    private final SequentialId creatureId;
    private final MetricsExtension.Impl metricsExt;

    private long decisionCycle = 0;

    public MemorySystemActor() {
        this(null, null);
    }

    public MemorySystemActor(SequentialId creatureId, MetricsExtension.Impl metricsExt) {
        this.creatureId = creatureId;
        this.metricsExt = metricsExt;
    }

    @Override
    public void addShortTermMemory(ShortTermMemory stm) {
        all.addLast(stm);
        byId.computeIfAbsent(stm.id(), k -> new ArrayList<>()).add(stm);

        if (all.size() > MAX_SIZE) {
            ShortTermMemory evicted = all.pollFirst();
            List<ShortTermMemory> bucket = byId.get(evicted.id());
            if (bucket != null) {
                bucket.remove(evicted);
                if (bucket.isEmpty()) byId.remove(evicted.id());
            }
        }

        if (metricsExt != null) {
            metricsExt.setGauge("dl2l_creature_memory_count", creatureId.toString(), all.size());
        }
    }

    @Override
    public List<ShortTermMemory> getMemories(SequentialId id) {
        List<ShortTermMemory> bucket = byId.get(id);
        return bucket != null ? new ArrayList<>(bucket) : new ArrayList<>();
    }

    @Override
    public void tickDecisionCycle() {
        decisionCycle++;
    }

    @Override
    public long currentDecisionCycle() {
        return decisionCycle;
    }

    @Override
    public List<Engram> reinforceWarmTraces(double emotionDelta, long currentCycle) {
        List<Engram> produced = new ArrayList<>();
        for (ShortTermMemory trace : all) {
            long gap = currentCycle - trace.cognitiveCycle();
            if (gap < 0) continue;
            double eligibility = Math.exp(-LAMBDA * gap);
            if (eligibility < Constants.MIN_TRACE_ELIGIBILITY) continue;

            Engram engram = new Engram(
                    trace.actionType(), trace.id(), trace.emotion(),
                    trace.perception(), trace.cognitiveCycle(),
                    emotionDelta * eligibility, eligibility, currentCycle);
            addEngram(engram);
            produced.add(engram);
        }
        return produced;
    }

    @Override
    public void addEngram(Engram engram) {
        engrams.add(engram);
        // Consummatory engrams are ALSO kept here, not moved: the general store still backs
        // consolidation and anything else reading the full trace history, so routing them away
        // from it would silently change those. This store exists to stop them being evicted, not
        // to reclassify them.
        if (engram.actionType() != null && engram.actionType().isConsummatory()) {
            consummatoryEngrams.add(engram);
        }
    }

    @Override
    public List<Engram> getRecentEngrams(int windowSize) {
        return engrams.recent(windowSize);
    }

    @Override
    public List<Engram> getRecentConsummatoryEngrams(int windowSize) {
        return consummatoryEngrams.recent(windowSize);
    }
}
