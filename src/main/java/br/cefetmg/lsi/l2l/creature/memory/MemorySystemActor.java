package br.cefetmg.lsi.l2l.creature.memory;

import br.cefetmg.lsi.l2l.common.SequentialId;

import java.util.*;
import java.util.stream.Collectors;

public class MemorySystemActor implements MemorySystem {

    private static final int MAX_SIZE = 1000;

    private final ArrayDeque<ShortTermMemory> all = new ArrayDeque<>();
    private final HashMap<SequentialId, List<ShortTermMemory>> byId = new HashMap<>();

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
    public List<ShortTermMemory> getRecentEngrams(int windowSize) {
        int skip = Math.max(0, all.size() - windowSize);
        return all.stream().skip(skip).collect(Collectors.toList());
    }
}
