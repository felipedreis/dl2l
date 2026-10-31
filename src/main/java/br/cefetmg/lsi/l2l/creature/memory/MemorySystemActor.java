package br.cefetmg.lsi.l2l.creature.memory;

import br.cefetmg.lsi.l2l.creature.ComponentRef;
import br.cefetmg.lsi.l2l.common.Constants;
import br.cefetmg.lsi.l2l.common.SequentialId;
import br.cefetmg.lsi.l2l.creature.arbiter.ClosedEpisodes;
import br.cefetmg.lsi.l2l.creature.arbiter.ClosedStep;
import br.cefetmg.lsi.l2l.creature.arbiter.DecisionRecord;
import br.cefetmg.lsi.l2l.creature.arbiter.EpisodeBuffer;
import br.cefetmg.lsi.l2l.creature.arbiter.FocusEvent;
import br.cefetmg.lsi.l2l.creature.arbiter.HomeostaticValue;
import br.cefetmg.lsi.l2l.metrics.MetricsExtension;

import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;
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

    private static final Logger LOGGER = Logger.getLogger(MemorySystemActor.class.getName());

    /** Issue #110: null until {@link #enableEpisodes}. Single writer: this actor. */
    private EpisodeBuffer episodes;
    private ComponentRef valuation;
    /** Steps the buffer wrote during the current call, sent to Valuation (or returned on death) at its end. */
    private final List<ClosedStep> written = new ArrayList<>();

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

    @Override
    public void enableEpisodes(ComponentRef valuation) {
        this.valuation = valuation;
        this.episodes = newEpisodeBuffer();
    }

    private EpisodeBuffer newEpisodeBuffer() {
        EpisodeBuffer buffer = new EpisodeBuffer(HomeostaticValue.defaults());
        buffer.addSink(written::addAll);
        return buffer;
    }

    @Override
    public void recordDecision(DecisionRecord d) {
        episodeCall(() -> episodes.onDecision(d.cycle(), d.action(), d.h(), d.focalDrive(), d.chosenQ()));
    }

    @Override
    public void recordFocusEvents(List<FocusEvent> events) {
        episodeCall(() -> events.forEach(episodes::onFocus));
    }

    @Override
    public void recordOutcome(double[] k) {
        episodeCall(() -> episodes.onOutcome((int) decisionCycle, k));
    }

    @Override
    public List<ClosedStep> closeOnDeath(int cycle, double[] lethalDeviation) {
        if (episodes == null) {
            return List.of();
        }
        written.clear();
        try {
            episodes.onDeath(cycle, lethalDeviation);
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING, "episode buffer: death close-out failed for " + creatureId, e);
        }
        List<ClosedStep> out = List.copyOf(written);
        written.clear();
        return out;
    }

    /**
     * Runs one buffer call, then sends what it wrote to Valuation. The episode bookkeeping is an add-on:
     * a broken tracker invariant or a bad vector drops the buffered episodes (a fresh buffer, so no
     * half-closed state survives) and the next {@code Opened} starts clean, instead of failing the
     * memory system that the cognitive cycle depends on.
     */
    private void episodeCall(Runnable call) {
        if (episodes == null) {
            return;
        }
        written.clear();
        try {
            call.run();
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING, "episode buffer reset for " + creatureId, e);
            episodes = newEpisodeBuffer();
            written.clear();
        }
        if (!written.isEmpty()) {
            valuation.tell(new ClosedEpisodes(written));
            written.clear();
        }
    }
}
