package br.cefetmg.lsi.l2l.creature.memory;

import br.cefetmg.lsi.l2l.common.SequentialId;

import java.util.List;

/**
 * Created by felipe on 23/08/17.
 */
public interface MemorySystem {

    void addShortTermMemory(ShortTermMemory stm);

    List<ShortTermMemory> getMemories(SequentialId id);

}
