package br.cefetmg.lsi.l2l.creature.arbiter;

import br.cefetmg.lsi.l2l.creature.common.Action;
import org.apache.commons.math3.special.Erf;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * The arbitration core of the uncertainty arbitration paper (section 4.5, {@code decide()} in pp. 11-12).
 * Pure: no actor, no I/O. The caller owns the {@link Random} and the controllers' estimators.
 *
 * <p>Controllers are consulted cheapest first. Each one's decisiveness is the confidence that its best
 * candidate really beats the second best, {@code conf = Phi((Q1 - Q2) / sqrt(SE1^2 + SE2^2))}. The first
 * controller whose {@code conf} reaches {@code theta} decides. If none does, the most decisive one
 * consulted decides, and on a tie the cheaper one does. The deciding controller's estimates are then
 * used to choose: with probability {@code epsilon} a candidate is picked uniformly (the floor), otherwise
 * each candidate's value is drawn as {@code N(Q, (tau * SE)^2)} and the largest draw is taken.
 *
 * <p>Consultation is in cost order because a controller's estimate is paid for before it is known whether
 * it is needed: the stopping rule is what keeps the expensive controllers from being asked on easy
 * decisions. Only the {@link ControllerType#OPERANT} controller is wired in R1, so the stopping rule is
 * not yet exercised in production.
 *
 * <p>Random draws, in order: one {@code nextDouble()} per decision with two or more candidates; then one
 * {@code nextInt(n)} if the floor fired, else one {@code nextGaussian()} per candidate in candidate order,
 * drawn even when an SE is zero, so the stream's consumption depends only on the number of candidates. A
 * single candidate draws nothing.
 *
 * <p>Not thread-safe. Owned by the actor that decides, like the {@link Random} it holds.
 */
public final class Arbiter {

    /** Controller families, in cost order. Only {@code OPERANT} is wired in R1 (issue #104). */
    public enum ControllerType { OPERANT, EPISODIC, PLANNER }

    /**
     * A controller: its family and the estimator that answers for it.
     *
     * @throws IllegalArgumentException if {@code type} or {@code estimator} is null
     */
    public record Controller(ControllerType type, ValueEstimator estimator) {

        public Controller {
            if (type == null || estimator == null) {
                throw new IllegalArgumentException("type and estimator must not be null");
            }
        }
    }

    /**
     * @param theta   decisiveness at which a controller stops the consultation, in [0.5, 1]
     * @param epsilon probability of the uniform floor, in [0, 1]
     * @param tau     scale of the Thompson draw; finite and non-negative. {@code 0} is pure greedy
     * @throws IllegalArgumentException if a value is out of range
     */
    public record Params(double theta, double epsilon, double tau) {

        /** The paper's values (section 4.5): theta = 0.8, epsilon = 0.03, tau = 1. */
        public static Params defaults() {
            return new Params(0.8, 0.03, 1.0);
        }

        public Params {
            if (!(theta >= 0.5 && theta <= 1.0)) {
                throw new IllegalArgumentException("theta must be in [0.5, 1]: " + theta);
            }
            if (!(epsilon >= 0.0 && epsilon <= 1.0)) {
                throw new IllegalArgumentException("epsilon must be in [0, 1]: " + epsilon);
            }
            if (!Double.isFinite(tau) || tau < 0.0) {
                throw new IllegalArgumentException("tau must be finite and non-negative: " + tau);
            }
        }
    }

    private final List<Controller> controllers;
    private final Params params;
    private final Random random;

    /**
     * @param cheapestFirst controllers in consultation order; at least one
     * @param params        stopping, floor and draw parameters
     * @param random        the stream every draw comes from; owned by the caller and not shared
     * @throws IllegalArgumentException if the list is empty or holds a null, or a parameter is null
     */
    public Arbiter(List<Controller> cheapestFirst, Params params, Random random) {
        if (cheapestFirst == null || cheapestFirst.isEmpty()) {
            throw new IllegalArgumentException("at least one controller is required");
        }
        for (Controller c : cheapestFirst) {
            if (c == null) {
                throw new IllegalArgumentException("controllers must not be null");
            }
        }
        if (params == null || random == null) {
            throw new IllegalArgumentException("params and random must not be null");
        }
        this.controllers = List.copyOf(cheapestFirst);
        this.params = params;
        this.random = random;
    }

    /**
     * Chooses one of the candidates.
     *
     * @param candidates candidate actions, in a fixed order; must not be empty and must hold no null
     * @param h          drive levels, passed to every controller; not mutated
     * @param focalDrive focal drive passed to every controller; {@code null} when no episode is open
     * @return the decision, with the chosen action and how it was chosen
     * @throws IllegalArgumentException if {@code candidates} is empty or holds a null, or {@code h} is null
     * @throws IllegalStateException    if a controller returns a list of the wrong size or with a null
     *                                  element: a bug in that controller, not a fallback case
     */
    public ArbitrationDecision decide(List<Action> candidates, double[] h, String focalDrive) {
        if (candidates == null || candidates.isEmpty()) {
            throw new IllegalArgumentException("candidates must not be empty");
        }
        if (h == null) {
            throw new IllegalArgumentException("h must not be null");
        }
        for (Action a : candidates) {
            if (a == null) {
                throw new IllegalArgumentException("candidates must not hold a null action");
            }
        }
        List<Action> cands = List.copyOf(candidates);
        int n = cands.size();

        List<ArbitrationDecision.Consultation> consultations = new ArrayList<>();
        List<Estimate> deciding = null;
        ControllerType decidingType = null;
        double bestConf = 0.0;
        for (Controller c : controllers) {
            List<Estimate> est = c.estimator().estimate(cands, h.clone(), focalDrive);
            checkShape(c.type(), est, n);
            double conf = decisiveness(est);
            consultations.add(new ArbitrationDecision.Consultation(c.type(), conf));
            if (conf > bestConf) {
                bestConf = conf;
                deciding = est;
                decidingType = c.type();
            }
            if (conf >= params.theta()) {
                break;
            }
        }

        if (n == 1) {
            return new ArbitrationDecision(1, consultations, decidingType, 0, cands.get(0),
                    deciding.get(0), null, ArbitrationDecision.ExplorationSource.SINGLE);
        }

        int chosenIndex;
        ArbitrationDecision.ExplorationSource source;
        if (random.nextDouble() < params.epsilon()) {
            chosenIndex = random.nextInt(n);
            source = ArbitrationDecision.ExplorationSource.EPSILON;
        } else {
            chosenIndex = 0;
            double bestDraw = Double.NEGATIVE_INFINITY;
            for (int i = 0; i < n; i++) {
                Estimate e = deciding.get(i);
                double draw = e.q() + params.tau() * e.se() * random.nextGaussian();
                if (draw > bestDraw) {
                    bestDraw = draw;
                    chosenIndex = i;
                }
            }
            source = chosenIndex == argmaxQ(deciding)
                    ? ArbitrationDecision.ExplorationSource.GREEDY
                    : ArbitrationDecision.ExplorationSource.THOMPSON;
        }

        return new ArbitrationDecision(n, consultations, decidingType, chosenIndex, cands.get(chosenIndex),
                deciding.get(chosenIndex), deciding.get(secondIndex(deciding)), source);
    }

    /**
     * Decisiveness of one controller's estimates, in [0.5, 1]: {@code Phi((Q1 - Q2) / sqrt(SE1^2 + SE2^2))}
     * over the two candidates with the largest Q (by value, not position; on equal Q the earlier one is
     * "1"). Degenerate cases, which the formula leaves undefined: one candidate gives 1 (nothing to tell
     * apart); equal Q gives 0.5; zero SEs with a gap give 1.
     *
     * @throws IllegalArgumentException if {@code estimates} is empty
     */
    static double decisiveness(List<Estimate> estimates) {
        if (estimates.isEmpty()) {
            throw new IllegalArgumentException("decisiveness needs at least one estimate");
        }
        if (estimates.size() == 1) {
            return 1.0;
        }
        int first = argmaxQ(estimates);
        int second = secondIndex(estimates);
        Estimate a = estimates.get(first);
        Estimate b = estimates.get(second);
        if (a.q() == b.q()) {
            return 0.5;
        }
        double variance = a.se() * a.se() + b.se() * b.se();
        if (variance == 0.0) {
            return 1.0;
        }
        double x = (a.q() - b.q()) / Math.sqrt(variance);
        return 0.5 * Erf.erfc(-x / Math.sqrt(2.0));
    }

    /** Index of the largest Q; on a tie the earlier candidate. */
    private static int argmaxQ(List<Estimate> estimates) {
        int best = 0;
        for (int i = 1; i < estimates.size(); i++) {
            if (estimates.get(i).q() > estimates.get(best).q()) {
                best = i;
            }
        }
        return best;
    }

    /**
     * Index of the second-largest Q: the largest among all candidates except the first-ranked one, on a tie
     * the earlier candidate. Requires at least two estimates.
     */
    private static int secondIndex(List<Estimate> estimates) {
        int first = argmaxQ(estimates);
        int second = -1;
        for (int i = 0; i < estimates.size(); i++) {
            if (i == first) continue;
            if (second < 0 || estimates.get(i).q() > estimates.get(second).q()) {
                second = i;
            }
        }
        return second;
    }

    private static void checkShape(ControllerType type, List<Estimate> est, int n) {
        if (est == null || est.size() != n) {
            throw new IllegalStateException(type + " returned " + (est == null ? "null" : est.size())
                    + " estimates for " + n + " candidates");
        }
        for (Estimate e : est) {
            if (e == null) {
                throw new IllegalStateException(type + " returned a null estimate");
            }
        }
    }
}
