package br.cefetmg.lsi.l2l.creature.memory;

import br.cefetmg.lsi.l2l.common.SequentialId;

import java.util.List;

public interface MemorySystem {

    void addShortTermMemory(ShortTermMemory stm);

    List<ShortTermMemory> getMemories(SequentialId id);

    List<ShortTermMemory> getRecentEngrams(int windowSize);
}
