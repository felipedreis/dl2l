package br.cefetmg.lsi.l2l.creature.memory;

import br.cefetmg.lsi.l2l.common.SequentialId;

import java.util.List;

public interface MemorySystem {

    void addShortTermMemory(ShortTermMemory stm);

    List<ShortTermMemory> getMemories(SequentialId id);

    // Called by FullAppraisal at the start of each onReceive so all components
    // share the same cognitive-cycle epoch.
    void tickDecisionCycle();

    long currentDecisionCycle();

    // Returns the newly produced Engrams so callers can persist them.
    List<Engram> reinforceWarmTraces(double emotionDelta, long currentCycle);

    void addEngram(Engram engram);

    List<Engram> getRecentEngrams(int windowSize);
}
