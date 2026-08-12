package br.cefetmg.lsi.l2l.creature.actionSelector;

import br.cefetmg.lsi.l2l.common.Constants;
import br.cefetmg.lsi.l2l.creature.bd.ActionSelectionType;
import br.cefetmg.lsi.l2l.creature.common.Action;
import br.cefetmg.lsi.l2l.creature.components.Emotion;
import br.cefetmg.lsi.l2l.creature.memory.Engram;
import br.cefetmg.lsi.l2l.creature.memory.MemorySystem;
import br.cefetmg.lsi.l2l.world.WorldObjectType;

import br.cefetmg.lsi.l2l.common.SequentialId;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;

/**
 * Episodic-memory action filter, after Mapa (2009) §5.3.2 and Campos et al. (2015) §III-C.
 *
 * <h2>What it decides</h2>
 * Memory chooses <em>what to engage with</em>, not <em>what to do to it</em>. It scores each
 * candidate {@link WorldObjectType} from the creature's own recent engrams, samples one, and
 * returns every candidate action targeting it — leaving the action choice to the operant table
 * ({@link ActionProbabilityFilter}), which runs next. This is Mapa's division of labour: her
 * FullAppraisal "seleciona o estímulo que possui maior valor de expectativa", then "o
 * condicionamento operante é acionado para selecionar a ação".
 *
 * <p>Before issue #88's follow-up this filter scored {@code (action, object)} pairs and returned a
 * <em>single</em> action, which ended the filter chain ({@link ActionSelection#selectOne} stops as
 * soon as one candidate remains). Because engrams are laid for every action taken, memory almost
 * always held APPROACH traces and rarely EAT ones, so it recommended approaching and terminated
 * before the filters that would have chosen EAT ever ran: creatures with memory enabled ate
 * 3.5-45x less and died sooner than their controls.
 *
 * <h2>How it scores</h2>
 * An object's value is the MEAN of {@code -emotionDelta × eligibility} over the <em>consummatory</em>
 * engrams mentioning it (a negative delta means aversive emotion fell — a good outcome). The mean,
 * not the sum: summing tracked how <em>often</em> an object had been engaged rather than how well
 * that went, and since acting lays more engrams the filter reinforced its own past choices
 * regardless of outcome.
 *
 * <p>Consummatory only, after Mapa (2009), who stores just {@code comer, tocar, brincar}. This
 * filter originally scored every action on the theory that eligibility traces propagate outcomes
 * back to the APPROACH that preceded them, so approach credit would be informative too. Measured
 * over the p84 pilot, it is not: EAT engrams discriminate GREEN/RED/GRAY apples by caloric value at
 * <b>6.3x</b>, APPROACH engrams at <b>1.09x</b>. Approach credit is object-blind, because an
 * approach's outcome depends on whatever the creature does next — the trace credits every recent
 * approach when a drive falls, whichever object was approached. Since approaches outnumber
 * consummatory acts 114:1, averaging the two together collapsed a 6.3x signal to 1.14x, which under
 * proportional sampling is barely a preference at all: creatures ate the same worthless
 * zero-calorie fruit as their no-memory controls and starved at the same rate.
 *
 * <h2>How it chooses</h2>
 * By weighted sampling, not argmax. Both source architectures are stochastic at exactly this
 * point (Mapa draws which memory to consult by emotional intensity; Campos selects "with a
 * probability proportional to this value"), and for a good reason: an argmax over a store written
 * by its own choices has a fixed point, where whatever wins gets reinforced and therefore keeps
 * winning. Weights are:
 *
 * <pre>
 *   prior            = MEMORY_NOVELTY_OPTIMISM · (mean positive score) · (1 + DA_NOVELTY_GAIN·tanh(daTonic))
 *   w(unknown)       = prior                          — novelty is mildly attractive, not invisible
 *   w(score  >  0)   = score                          — proportional selection (Herrnstein matching)
 *   w(score <= 0)    = MEMORY_NEGATIVE_FLOOR · prior  — Campos's rule without his absorbing state
 * </pre>
 *
 * Gates (all pass the candidates through untouched, so a later filter decides):
 * <ol>
 *   <li>Fewer than two candidates — nothing to disambiguate.</li>
 *   <li>Empty engram buffer — no experience yet.</li>
 *   <li>All candidates target the same object — memory has no object-level choice to make.</li>
 *   <li>No candidate object has any engram evidence — memory has no basis to prefer one, and
 *       passing through leaves the choice to the operant table, which is better informed than a
 *       uniform draw would be. This is where Campos's {@code Random()} fall-through ends up.</li>
 * </ol>
 */
public class MemoryFilter implements ActionFilter {

    private final MemorySystem memory;
    private final Random random;

    /** Multiplier on the novelty prior from tonic dopamine; 1.0 = unmodulated. */
    private double noveltyGain = 1.0;

    public MemoryFilter(MemorySystem memory) {
        // No-arg Random() self-seeds from an atomic uniquifier ^ nanoTime, so filters created in
        // the same millisecond are not correlated — same reasoning as ActionProbabilityFilter.
        this(memory, new Random());
    }

    /** Seeded constructor, for deterministic tests. */
    MemoryFilter(MemorySystem memory, Random random) {
        this.memory = memory;
        this.random = random;
    }

    /**
     * Applies tonic dopamine to the novelty prior, mirroring
     * {@link ActionProbabilityFilter#setModulation(double, double)}. Left unmodulated (gain 1.0)
     * the filter reproduces its non-neuromodulated behaviour exactly, which is what the legacy
     * parity arms run.
     */
    public void setModulation(double daTonic) {
        this.noveltyGain = 1.0 + Constants.DA_NOVELTY_GAIN * Math.tanh(Math.max(0.0, daTonic));
    }

    @Override
    public List<Action> filter(List<Action> actions, Emotion toRegulate) {
        // Gate 1 — nothing to disambiguate
        if (actions.size() <= 1) {
            passThrough(0, actions.size(), countObjects(actions), 0);
            return actions;
        }

        List<Engram> engrams = memory.getRecentConsummatoryEngrams(Constants.MEMORY_FILTER_WINDOW);

        // Gate 2 — no experience yet
        if (engrams.isEmpty()) {
            passThrough(0, actions.size(), countObjects(actions), 0);
            return actions;
        }

        Map<WorldObjectType, Double> scores = scoreObjects(engrams);

        // Candidate actions grouped by the object they target. LinkedHashMap so the weighted draw
        // is reproducible from a seeded Random regardless of hash order.
        Map<WorldObjectType, List<Action>> byObject = new LinkedHashMap<>();
        for (Action a : actions) {
            byObject.computeIfAbsent(a.perception.objectType.getOrElse(null), k -> new ArrayList<>())
                    .add(a);
        }

        int scoredCount = 0;
        for (WorldObjectType t : byObject.keySet()) {
            if (scores.containsKey(t)) scoredCount++;
        }

        // Gate 3 — one object in play; the action choice belongs to the operant table
        if (byObject.size() <= 1) {
            passThrough(engrams.size(), actions.size(), byObject.size(), scoredCount);
            return actions;
        }

        double prior = noveltyPrior(byObject.keySet(), scores);

        Map<WorldObjectType, Double> weights = new LinkedHashMap<>();
        double totalWeight = 0.0;
        for (WorldObjectType t : byObject.keySet()) {
            Double s = scores.get(t);
            double w;
            if (s == null) {
                w = prior;                                      // unexplored
            } else if (s > 0) {
                w = s;                                          // proportional to remembered value
            } else {
                w = Constants.MEMORY_NEGATIVE_FLOOR * prior;    // remembered as harmful
            }
            weights.put(t, w);
            totalWeight += w;
        }

        // Gate 4 — nothing positive is known and there is no novelty to be drawn to
        if (totalWeight <= 0.0) {
            passThrough(engrams.size(), actions.size(), byObject.size(), scoredCount);
            return actions;
        }

        WorldObjectType chosen = sample(weights, totalWeight);
        List<Action> result = byObject.get(chosen);

        record(engrams.size(), actions.size(), byObject.size(), scoredCount, result.size(), true,
                scoreOrNaN(scores, chosen), bestOtherScore(scores, byObject.keySet(), chosen),
                chosen, result.get(0).perception.id);
        return result;
    }

    /** Distinct objects the candidate actions target — the denominator for {@code scored}. */
    private static int countObjects(List<Action> actions) {
        Set<WorldObjectType> seen = new HashSet<>();
        for (Action a : actions) {
            seen.add(a.perception.objectType.getOrElse(null));
        }
        return seen.size();
    }

    /** Mean of {@code -emotionDelta × eligibility} per object type over the engram window. */
    private static Map<WorldObjectType, Double> scoreObjects(List<Engram> engrams) {
        Map<WorldObjectType, double[]> totals = new HashMap<>();   // type -> {sum, count}
        for (Engram e : engrams) {
            WorldObjectType objType = e.perception().objectType.getOrElse(null);
            double[] acc = totals.computeIfAbsent(objType, k -> new double[2]);
            acc[0] += -e.emotionDelta() * e.eligibility();
            acc[1] += 1;
        }
        Map<WorldObjectType, Double> scores = new HashMap<>();
        totals.forEach((t, acc) -> scores.put(t, acc[0] / acc[1]));
        return scores;
    }

    /**
     * What an unexplored object is worth: the mean value of the candidate objects currently known
     * to be good, scaled by optimism and tonic dopamine.
     *
     * <p>When nothing on offer is known-good the mean magnitude of what <em>is</em> known stands in,
     * so the prior stays on the same scale as the scores. Without that fallback a choice between an
     * object remembered as harmful and an unexplored one would give both a weight of zero, and
     * memory would decline to express the very preference it is most confident about.
     *
     * <p>Zero only when no candidate has any evidence at all — and then memory genuinely has
     * nothing to say, so gate 4 passes the candidates through to the operant table rather than
     * picking an object at random.
     */
    private double noveltyPrior(Iterable<WorldObjectType> candidates,
                                Map<WorldObjectType, Double> scores) {
        double positiveSum = 0.0;
        int positiveCount = 0;
        double magnitudeSum = 0.0;
        int scoredCount = 0;
        for (WorldObjectType t : candidates) {
            Double s = scores.get(t);
            if (s == null) continue;
            scoredCount++;
            magnitudeSum += Math.abs(s);
            if (s > 0) { positiveSum += s; positiveCount++; }
        }
        double base;
        if (positiveCount > 0)   base = positiveSum / positiveCount;
        else if (scoredCount > 0) base = magnitudeSum / scoredCount;
        else                      base = 0.0;
        return Constants.MEMORY_NOVELTY_OPTIMISM * base * noveltyGain;
    }

    private WorldObjectType sample(Map<WorldObjectType, Double> weights, double totalWeight) {
        double r = random.nextDouble() * totalWeight;
        WorldObjectType last = null;
        for (Map.Entry<WorldObjectType, Double> e : weights.entrySet()) {
            last = e.getKey();
            r -= e.getValue();
            if (r <= 0) return last;
        }
        return last;   // floating-point residue only
    }

    private static double scoreOrNaN(Map<WorldObjectType, Double> scores, WorldObjectType t) {
        Double s = scores.get(t);
        return s == null ? Double.NaN : s;
    }

    /** Best score among the candidate objects that were not chosen; NaN if none of them is known. */
    private static double bestOtherScore(Map<WorldObjectType, Double> scores,
                                         Iterable<WorldObjectType> candidates,
                                         WorldObjectType chosen) {
        double best = Double.NaN;
        for (WorldObjectType t : candidates) {
            if (t == chosen) continue;
            Double s = scores.get(t);
            if (s != null && (Double.isNaN(best) || s > best)) best = s;
        }
        return best;
    }

    /**
     * What the filter had to go on the last time it was consulted, for {@code MemoryDecisionState}.
     *
     * <p>{@code returned} is how many candidate actions survived: equal to {@code candidates} on
     * every pass-through, smaller when memory narrowed to one object. {@code scored} counts the
     * candidate <em>objects</em> memory had evidence about, out of {@code objects} — so the two
     * ratios that matter, how much of the choice set is remembered ({@code scored/objects}) and how
     * far memory narrowed it ({@code returned/candidates}), are both computable. {@code
     * winningScore} is the chosen object's value and {@code runnerUpScore} the best among the
     * objects passed over — NaN where there was no evidence, so "did memory sample the argmax?" is
     * answerable after the fact.
     */
    public record Decision(int engramWindow, int candidates, int objects, int scored, int returned,
                           double winningScore, double runnerUpScore, boolean decided,
                           WorldObjectType objectType, SequentialId target) {}

    private Decision lastDecision;

    private void passThrough(int engramWindow, int candidates, int objects, int scoredCount) {
        record(engramWindow, candidates, objects, scoredCount, candidates, false,
                Double.NaN, Double.NaN, null, null);
    }

    private void record(int engramWindow, int candidates, int objects, int scoredCount, int returned,
                        boolean decided, double winningScore, double runnerUpScore,
                        WorldObjectType objectType, SequentialId target) {
        lastDecision = new Decision(engramWindow, candidates, objects, scoredCount, returned,
                winningScore, runnerUpScore, decided, objectType, target);
    }

    /**
     * Returns the last consultation's record and clears it, so a caller polling once per cognitive
     * cycle cannot persist the same decision twice on a cycle where this filter was never reached
     * ({@code ActionSelection.selectOne} stops early once a filter narrows to one candidate).
     */
    public Optional<Decision> takeLastDecision() {
        Decision d = lastDecision;
        lastDecision = null;
        return Optional.ofNullable(d);
    }

    @Override
    public ActionSelectionType getFilterType() {
        return ActionSelectionType.MEMORY;
    }
}
