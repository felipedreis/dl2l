package br.cefetmg.lsi.l2l.creature.arbiter;

import br.cefetmg.lsi.l2l.creature.common.Action;
import br.cefetmg.lsi.l2l.creature.common.ActionType;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Holds the decisions of open episodes and writes them out when their episode closes, with the
 * discounted return {@code G_t = sum_j gamma^j K_{t+j}} (uncertainty arbitration paper, section 4.2).
 *
 * <p>Input, all stamped with the decision cycle:
 * <ul>
 *   <li>{@link #onDecision}: one decision. It is a step of the open segment of its focal drive;
 *       with no focal drive, or no open segment for it, it is not buffered;</li>
 *   <li>{@link #onOutcome}: the attributed realised K of an act, stamped with the cycle it arrived in;</li>
 *   <li>{@link #onFocus}: an episode event of the {@link FocusTracker};</li>
 *   <li>{@link #onDeath}: the creature died, with the lethal deviation as its last outcome.</li>
 * </ul>
 *
 * <p>Closing rules. A {@code Closed} event does not write at once: the segment is held until a
 * decision with cycle {@code >= closeCycle + settleCycles} arrives, so that outcomes stamped up to the
 * close cycle, but delivered late through another channel, are still counted. Death writes at once.
 * A {@code Suspended} segment is written at the cut cycle; the same episode resumed later opens a new
 * segment that is never mixed with the flushed one. Each written step gets
 * <ul>
 *   <li>regulated: {@code G_t} summed up to the close cycle; {@code value = V(G_t | H_t)};</li>
 *   <li>pre-empted: {@code G_t} cut at the cut cycle; {@code value = V(G_t | H_t) + gamma^(T-t) B}, with
 *       {@code B} the chosen Q of the decision at the cut cycle ({@code 0} while that Q is NaN);</li>
 *   <li>timed out: {@code G = 0} and {@code value = 0} for APPROACH steps; every other step keeps its
 *       Monte Carlo return;</li>
 *   <li>death: as regulated, with the lethal deviation included.</li>
 * </ul>
 *
 * <p>Single writer, not thread-safe: owned by one actor ({@code Valuation}). No Akka, no I/O.
 */
public final class EpisodeBuffer {

    /** Eligibility-trace half-life of 5 cycles: {@code gamma = 2^(-1/5)}, as in the existing traces. */
    public static final double DEFAULT_GAMMA = Math.pow(2, -1.0 / 5);

    /** The close is held for one cycle before it is written (see the class javadoc). */
    public static final int DEFAULT_SETTLE_CYCLES = 1;

    private static final Logger LOGGER = Logger.getLogger(EpisodeBuffer.class.getName());

    private static final class Step {
        final int cycle;
        final Action action;
        final double[] h;
        final double chosenQ;

        Step(int cycle, Action action, double[] h, double chosenQ) {
            this.cycle = cycle;
            this.action = action;
            this.h = h;
            this.chosenQ = chosenQ;
        }
    }

    private static final class Segment {
        final long episodeId;
        final String drive;
        final int openCycle;
        final List<Step> steps = new ArrayList<>();

        Segment(long episodeId, String drive, int openCycle) {
            this.episodeId = episodeId;
            this.drive = drive;
            this.openCycle = openCycle;
        }
    }

    /** A segment whose close has been seen but which is not written yet. */
    private record Pending(Segment segment, int closeCycle, ClosedStep.CloseType closeType) {
    }

    private final HomeostaticValue value;
    private final double gamma;
    private final int settleCycles;
    private final List<ReturnSink> sinks = new ArrayList<>();

    /** Attributed K by arrival cycle. Entries older than every live segment are pruned. */
    private final TreeMap<Integer, double[]> outcomes = new TreeMap<>();
    /** Chosen Q by decision cycle, kept until a pre-empted close that needs it has been written. */
    private final TreeMap<Integer, Double> chosenQ = new TreeMap<>();
    /** Episodes suspended by pre-emption, by drive, awaiting a resume or a close. */
    private final Map<String, Long> suspended = new HashMap<>();
    private final Deque<Pending> pending = new ArrayDeque<>();
    private Segment open;
    private long nextEpisodeId = 0;
    /** Latest cycle seen on a decision or an outcome; anchors the pre-roll of {@link #outcomes}. */
    private int latestCycle = Integer.MIN_VALUE;

    /**
     * @param value        the value function {@code V(G | H)} applied to every written step
     * @param gamma        per-cycle discount, in (0, 1]
     * @param settleCycles cycles a close is held before it is written; at least 1
     * @throws IllegalArgumentException if {@code value} is null, {@code gamma} is not in (0, 1], or
     *                                  {@code settleCycles} is below 1
     */
    public EpisodeBuffer(HomeostaticValue value, double gamma, int settleCycles) {
        if (value == null) {
            throw new IllegalArgumentException("value must not be null");
        }
        if (!(gamma > 0 && gamma <= 1)) {
            throw new IllegalArgumentException("gamma must be in (0, 1]: " + gamma);
        }
        if (settleCycles < 1) {
            throw new IllegalArgumentException("settleCycles must be at least 1: " + settleCycles);
        }
        this.value = value;
        this.gamma = gamma;
        this.settleCycles = settleCycles;
    }

    /** Default discount {@link #DEFAULT_GAMMA} and settle {@link #DEFAULT_SETTLE_CYCLES}. */
    public EpisodeBuffer(HomeostaticValue value) {
        this(value, DEFAULT_GAMMA, DEFAULT_SETTLE_CYCLES);
    }

    /**
     * Registers a sink; it is called with every segment written after this call.
     *
     * @throws IllegalArgumentException if {@code sink} is null
     */
    public void addSink(ReturnSink sink) {
        if (sink == null) {
            throw new IllegalArgumentException("sink must not be null");
        }
        sinks.add(sink);
    }

    /**
     * One decision of the arbiter. Writes any held close that the decision settles, then buffers the
     * step if {@code focalDrive} is the drive of the open segment.
     *
     * @param cycle      decision cycle
     * @param action     action chosen; must not be null
     * @param h          drive levels at decision time, in {@link FocusTracker#DRIVES} order; copied
     * @param focalDrive focal drive of the decision, or {@code null} when no episode was open
     * @param chosenQ    the arbiter's Q of the chosen candidate; {@code NaN} until #104
     * @throws IllegalArgumentException if {@code action} is null, {@code h} has the wrong dimension or a
     *                                  non-finite entry, or {@code chosenQ} is infinite
     */
    public void onDecision(int cycle, Action action, double[] h, String focalDrive, double chosenQ) {
        if (action == null) {
            throw new IllegalArgumentException("action must not be null");
        }
        checkVector(h, "h");
        if (Double.isInfinite(chosenQ)) {
            throw new IllegalArgumentException("chosenQ must not be infinite: " + chosenQ);
        }
        this.chosenQ.put(cycle, chosenQ);
        latestCycle = Math.max(latestCycle, cycle);
        writeSettled(cycle);
        if (focalDrive != null && open != null && open.drive.equals(focalDrive)) {
            open.steps.add(new Step(cycle, action, h.clone(), chosenQ));
        }
        pruneChosenQ(cycle);
        pruneOutcomes();
    }

    /**
     * Realised K of one attributed act, stamped with the cycle it arrived in. With no segment live it is
     * kept for a short pre-roll (see {@link #pruneOutcomes}), since the K of an episode's first act can
     * arrive before the {@code Opened} event that makes the segment live.
     *
     * @throws IllegalArgumentException if {@code k} has the wrong dimension or a non-finite entry
     */
    public void onOutcome(int cycle, double[] k) {
        checkVector(k, "k");
        latestCycle = Math.max(latestCycle, cycle);
        addToOutcomes(cycle, k);
        pruneOutcomes();
    }

    /**
     * An episode event of the {@link FocusTracker}, in the order the tracker emitted it.
     *
     * @throws IllegalStateException    if the event does not match the open or suspended episodes (a
     *                                  tracker invariant has been broken)
     * @throws IllegalArgumentException if {@code event} is null
     */
    public void onFocus(FocusEvent event) {
        if (event == null) {
            throw new IllegalArgumentException("event must not be null");
        }
        switch (event) {
            case FocusEvent.Opened e -> {
                requireNoOpen(e);
                open = new Segment(nextEpisodeId++, e.drive(), e.cycle());
            }
            case FocusEvent.Suspended e -> {
                requireOpen(e.drive(), e);
                suspended.put(e.drive(), open.episodeId);
                pending.addLast(new Pending(open, e.cycle(), ClosedStep.CloseType.PREEMPTED));
                open = null;
            }
            case FocusEvent.Resumed e -> {
                Long episodeId = suspended.remove(e.drive());
                if (episodeId == null) {
                    throw new IllegalStateException("Resumed for a drive that is not suspended: " + e);
                }
                requireNoOpen(e);
                open = new Segment(episodeId, e.drive(), e.cycle());
            }
            case FocusEvent.Closed e -> {
                if (open != null && open.drive.equals(e.drive())) {
                    pending.addLast(new Pending(open, e.cycle(), closeTypeOf(e.outcome())));
                    open = null;
                } else if (suspended.remove(e.drive()) == null) {
                    throw new IllegalStateException("Closed for a drive with no episode: " + e);
                }
            }
        }
        pruneOutcomes();
    }

    /**
     * The creature died. The lethal deviation is the last outcome, stamped with {@code cycle}; held closes
     * are written at their own cycles, then the open segment is written as a death. Suspended episodes were
     * already written at their cut and only lose their bookkeeping.
     *
     * @param cycle            cycle of death
     * @param lethalDeviation  the outcome that killed the creature, in {@link FocusTracker#DRIVES} order
     * @throws IllegalArgumentException if {@code lethalDeviation} has the wrong dimension or a non-finite entry
     */
    public void onDeath(int cycle, double[] lethalDeviation) {
        checkVector(lethalDeviation, "lethalDeviation");
        if (open != null || !pending.isEmpty()) {
            addToOutcomes(cycle, lethalDeviation);
        }
        while (!pending.isEmpty()) {
            Pending p = pending.pollFirst();
            emit(close(p.segment, p.closeCycle, p.closeType));
        }
        if (open != null) {
            emit(close(open, cycle, ClosedStep.CloseType.DEATH));
            open = null;
        }
        suspended.clear();
        outcomes.clear();
        chosenQ.clear();
    }

    /** Writes every held close whose settle window has passed at {@code cycle}. */
    private void writeSettled(int cycle) {
        while (!pending.isEmpty() && pending.peekFirst().closeCycle + settleCycles <= cycle) {
            Pending p = pending.pollFirst();
            emit(close(p.segment, p.closeCycle, p.closeType));
        }
    }

    /**
     * Builds the steps of one segment: backward Monte Carlo pass over the cycles up to the cut, then the
     * value of every step, with the close-type rules applied.
     */
    private List<ClosedStep> close(Segment seg, int closeCycle, ClosedStep.CloseType closeType) {
        if (seg.steps.isEmpty()) {
            return List.of();
        }
        if (seg.steps.get(seg.steps.size() - 1).cycle > closeCycle) {
            throw new IllegalStateException("step at cycle " + seg.steps.get(seg.steps.size() - 1).cycle
                    + " is after the close at " + closeCycle + " of " + seg.drive);
        }
        int dim = value.dimension();
        double bootstrapB = closeType == ClosedStep.CloseType.PREEMPTED ? bootstrapAt(closeCycle) : Double.NaN;

        double[][] returns = new double[seg.steps.size()][];
        double[] running = new double[dim];
        int idx = seg.steps.size() - 1;
        int first = seg.steps.get(0).cycle;
        for (int c = closeCycle; c >= first; c--) {
            double[] k = outcomes.get(c);
            for (int i = 0; i < dim; i++) {
                running[i] = (k == null ? 0 : k[i]) + gamma * running[i];
            }
            while (idx >= 0 && seg.steps.get(idx).cycle == c) {
                returns[idx--] = running.clone();
            }
        }

        List<ClosedStep> out = new ArrayList<>(seg.steps.size());
        for (int i = 0; i < seg.steps.size(); i++) {
            Step s = seg.steps.get(i);
            boolean zeroed = closeType == ClosedStep.CloseType.TIMED_OUT
                    && s.action.type == ActionType.APPROACH;
            double[] g = zeroed ? new double[dim] : returns[i];
            double v = zeroed ? 0 : value.value(g, s.h);
            double bootstrap = Double.NaN;
            if (closeType == ClosedStep.CloseType.PREEMPTED) {
                bootstrap = bootstrapB;
                v += Math.pow(gamma, closeCycle - s.cycle) * bootstrapB;
            }
            out.add(new ClosedStep(seg.episodeId, seg.drive, closeType, s.cycle, closeCycle,
                    s.action, s.h, g, bootstrap, v));
        }
        return out;
    }

    /** B for a pre-empted cut at {@code cutCycle}: the chosen Q of that decision, or 0 while unknown. */
    private double bootstrapAt(int cutCycle) {
        Double q = chosenQ.get(cutCycle);
        return q == null || Double.isNaN(q) ? 0 : q;
    }

    private void emit(List<ClosedStep> steps) {
        if (steps.isEmpty()) {
            return;
        }
        List<ClosedStep> unmodifiable = List.copyOf(steps);
        for (ReturnSink sink : sinks) {
            try {
                sink.onClosed(unmodifiable);
            } catch (RuntimeException e) {
                // One failing sink must not starve the others or abort the close of the rest of the queue.
                LOGGER.log(Level.WARNING, "return sink failed for " + unmodifiable.size() + " closed steps", e);
            }
        }
    }

    private void addToOutcomes(int cycle, double[] k) {
        double[] sum = outcomes.computeIfAbsent(cycle, c -> new double[value.dimension()]);
        for (int i = 0; i < sum.length; i++) {
            sum[i] += k[i];
        }
    }

    /**
     * Drops outcomes no live segment can still need: those before the oldest live segment's open cycle.
     * With no live segment, keeps a pre-roll of the last {@code settleCycles + 1} cycles for an
     * {@code Opened} that has not been processed yet.
     */
    private void pruneOutcomes() {
        Integer lowest = null;
        if (!pending.isEmpty()) {
            lowest = pending.peekFirst().segment.openCycle;
        } else if (open != null) {
            lowest = open.openCycle;
        }
        if (lowest == null) {
            if (latestCycle != Integer.MIN_VALUE) {
                outcomes.headMap(latestCycle - settleCycles - 1).clear();
            }
        } else {
            outcomes.headMap(lowest).clear();
        }
    }

    /** Keeps the chosen Q that a held pre-empted close may still need, plus the latest decision. */
    private void pruneChosenQ(int latestCycle) {
        int keepFrom = latestCycle;
        if (!pending.isEmpty()) {
            keepFrom = Math.min(keepFrom, pending.peekFirst().closeCycle);
        }
        chosenQ.headMap(keepFrom).clear();
    }

    private void requireNoOpen(FocusEvent e) {
        if (open != null) {
            throw new IllegalStateException("episode for " + open.drive + " is still open at " + e);
        }
    }

    private void requireOpen(String drive, FocusEvent e) {
        if (open == null || !open.drive.equals(drive)) {
            throw new IllegalStateException("no open episode for " + drive + " at " + e);
        }
    }

    private static ClosedStep.CloseType closeTypeOf(FocusEvent.Outcome outcome) {
        return switch (outcome) {
            case REGULATED -> ClosedStep.CloseType.REGULATED;
            case TIMED_OUT -> ClosedStep.CloseType.TIMED_OUT;
            case DEATH -> ClosedStep.CloseType.DEATH;
        };
    }

    private void checkVector(double[] v, String name) {
        if (v == null || v.length != value.dimension()) {
            throw new IllegalArgumentException(name + " must have " + value.dimension() + " entries");
        }
        for (int i = 0; i < v.length; i++) {
            if (!Double.isFinite(v[i])) {
                throw new IllegalArgumentException(name + "[" + i + "] is not finite: " + v[i]);
            }
        }
    }
}
