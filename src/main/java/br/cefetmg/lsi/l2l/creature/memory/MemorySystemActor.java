package br.cefetmg.lsi.l2l.creature.memory;

import br.cefetmg.lsi.l2l.common.SequentialId;

import java.util.ArrayList;
import java.util.List;

/**
 * Created by felipe on 01/09/17.
 */
public class MemorySystemActor implements MemorySystem {

    @Override
    public void addShortTermMemory(ShortTermMemory stm) {

    }

    @Override
    public List<ShortTermMemory> getMemories(SequentialId id) {
        return new ArrayList<>();
    }
}
