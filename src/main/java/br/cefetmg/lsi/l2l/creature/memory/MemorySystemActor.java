package br.cefetmg.lsi.l2l.creature.memory;

import br.cefetmg.lsi.l2l.common.Constants;
import br.cefetmg.lsi.l2l.common.SequentialId;

import java.util.*;
import java.util.stream.Collectors;

public class MemorySystemActor implements MemorySystem {

    private static final int MAX_SIZE = 1000;
    private static final int MAX_ENGRAM_SIZE = 1000;
    private static final double LAMBDA = Math.log(2) / Constants.TRACE_DECAY_HALF_LIFE;

    private final ArrayDeque<ShortTermMemory> all = new ArrayDeque<>();
    private final HashMap<SequentialId, List<ShortTermMemory>> byId = new HashMap<>();
    private final ArrayDeque<Engram> engrams = new ArrayDeque<>();

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
    }

    @Override
    public List<ShortTermMemory> getMemories(SequentialId id) {
        List<ShortTermMemory> bucket = byId.get(id);
        return bucket != null ? new ArrayList<>(bucket) : new ArrayList<>();
    }

    @Override
    public void reinforceWarmTraces(double emotionDelta, long currentCycle) {
        for (ShortTermMemory trace : all) {
            long gap = currentCycle - trace.cognitiveCycle();
            if (gap < 0) continue;
            double eligibility = Math.exp(-LAMBDA * gap);
            if (eligibility < Constants.MIN_TRACE_ELIGIBILITY) continue;

            addEngram(new Engram(
                    trace.actionType(), trace.id(), trace.emotion(),
                    trace.perception(), trace.cognitiveCycle(),
                    emotionDelta * eligibility, currentCycle));
        }
    }

    @Override
    public void addEngram(Engram engram) {
        engrams.addLast(engram);
        if (engrams.size() > MAX_ENGRAM_SIZE) {
            engrams.pollFirst();
        }
    }

    @Override
    public List<Engram> getRecentEngrams(int windowSize) {
        int skip = Math.max(0, engrams.size() - windowSize);
        return engrams.stream().skip(skip).collect(Collectors.toList());
    }
}
