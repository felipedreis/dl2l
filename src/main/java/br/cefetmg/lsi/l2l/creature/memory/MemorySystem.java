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

    /**
     * The most recent engrams laid by consummatory acts, held in their own bounded store.
     *
     * <p>Separate from {@link #getRecentEngrams} because the two populations differ by two orders
     * of magnitude in both volume and information. Consummatory acts are 0.82% of engrams, so a
     * single shared FIFO of 1000 retains roughly EIGHT of them while a creature accumulates ~720
     * feedings in its life — the store discards almost all of its most informative experience to
     * make room for approach traces that carry no object-specific signal. Worse, at ~290
     * engrams/second the shared store turns over completely every 3.45 s, so anything written into
     * it (including consolidated traces) is evicted almost immediately.
     */
    List<Engram> getRecentConsummatoryEngrams(int windowSize);
}
