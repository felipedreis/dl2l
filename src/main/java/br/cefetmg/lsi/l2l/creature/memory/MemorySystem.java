package br.cefetmg.lsi.l2l.creature.memory;

import br.cefetmg.lsi.l2l.common.SequentialId;

import br.cefetmg.lsi.l2l.creature.ComponentRef;
import br.cefetmg.lsi.l2l.creature.arbiter.ClosedStep;
import br.cefetmg.lsi.l2l.creature.arbiter.DecisionRecord;
import br.cefetmg.lsi.l2l.creature.arbiter.FocusEvent;

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

    /**
     * Issue #110: turns on the episode buffer, a write-only working memory between the arbiter's
     * decisions and the valuation of their outcomes. Until called (it is not, with focalDriveEnabled
     * off) the {@code record*} methods do nothing. Steps the buffer writes are sent to {@code valuation}
     * as a {@code ClosedEpisodes} message.
     */
    void enableEpisodes(ComponentRef valuation);

    /** One decision, from FullAppraisal. Asynchronous. */
    void recordDecision(DecisionRecord decision);

    /** The tracker's episode events of one cycle, in emission order, from FullAppraisal. Asynchronous. */
    void recordFocusEvents(List<FocusEvent> events);

    /**
     * The attributed realised K of one act, from HomeostaticRegulation, in {@code FocusTracker.DRIVES}
     * order. Asynchronous; stamped with this system's decision cycle when it is processed.
     */
    void recordOutcome(double[] k);

    /**
     * The creature died: closes the open and held episodes with the lethal deviation as their last
     * outcome and returns the written steps. Synchronous, because the caller is about to stop; empty
     * when the episodes are not enabled.
     */
    List<ClosedStep> closeOnDeath(int cycle, double[] lethalDeviation);
}
