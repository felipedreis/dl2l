package br.cefetmg.lsi.l2l.creature.arbiter;

import br.cefetmg.lsi.l2l.creature.common.Action;

import java.util.List;

/**
 * What the {@link Arbiter} did on one decision: the controllers it consulted and how decisive each was,
 * the one whose estimates it drew from, the action it chose and how it was chosen.
 *
 * <p>The decision telemetry (#105) reads everything it persists from this record, so it never recomputes a
 * choice. Immutable: the lists are copied on the way in.
 *
 * @param candidateCount     number of candidates the arbiter chose among
 * @param candidates         the candidates, in the order the arbiter was given them
 * @param estimates          the deciding controller's estimate of each candidate, in the same order; this is
 *                           what lets the caller read the Q of an action other than the chosen one (the
 *                           hysteresis gate can replace the choice)
 * @param consultations      the controllers consulted, cheapest first, with each one's decisiveness
 * @param decidingController the controller whose estimates were drawn from and reported
 * @param mostExpensiveAffordable the most expensive controller the budget allows (#105); with no budget yet,
 *                           the most expensive wired one. Not necessarily consulted
 * @param chosenIndex        index of the chosen action in the candidate list
 * @param chosen             the chosen action
 * @param chosenEstimate     the deciding controller's estimate of the chosen action
 * @param bestEstimate       the deciding controller's estimate of the candidate with the highest Q (ties: the
 *                           earliest); equal to {@code chosenEstimate} when there is one candidate (#105)
 * @param runnerUpEstimate   the deciding controller's estimate of the candidate with the second-highest
 *                           Q; {@code null} when there is one candidate
 * @param nTiedAtMax         how many candidates have exactly the highest Q; 1 when the best is unique (#105)
 * @param source             how the action was picked
 * @param noveltyBonus       the bonus the Thompson draw added to the chosen candidate, {@code noveltyScale / sqrt(1 +
 *                           n_eff)}; 0 when the deciding controller gives no neighbour count (#114)
 */
public record ArbitrationDecision(int candidateCount,
                                  List<Action> candidates,
                                  List<Estimate> estimates,
                                  List<Consultation> consultations,
                                  Arbiter.ControllerType decidingController,
                                  Arbiter.ControllerType mostExpensiveAffordable,
                                  int chosenIndex,
                                  Action chosen,
                                  Estimate chosenEstimate,
                                  Estimate bestEstimate,
                                  Estimate runnerUpEstimate,
                                  int nTiedAtMax,
                                  ExplorationSource source,
                                  double noveltyBonus) {

    /**
     * One controller's answer to the stopping rule.
     *
     * @param controller the controller consulted
     * @param conf       its decisiveness, in [0.5, 1]
     * @param estimates  that controller's estimate of each candidate, in candidate order (#105: lets the
     *                   telemetry read the Q of a non-chosen action per controller, without asking again)
     */
    public record Consultation(Arbiter.ControllerType controller, double conf, List<Estimate> estimates) {

        public Consultation {
            if (controller == null || estimates == null) {
                throw new IllegalArgumentException("controller and estimates must not be null");
            }
            estimates = List.copyOf(estimates);
        }
    }

    /**
     * How the chosen action was picked. {@code SINGLE}: one candidate, nothing to choose among.
     * {@code EPSILON}: the uniform floor. {@code GREEDY}: the chosen candidate's Q equals the highest Q, so the
     * draw was as good as the best (possibly one of several tied; #105). {@code THOMPSON}: the draw picked a
     * candidate with a lower Q than the highest.
     */
    public enum ExplorationSource { SINGLE, GREEDY, THOMPSON, EPSILON }

    public ArbitrationDecision {
        if (consultations == null || decidingController == null || mostExpensiveAffordable == null
                || chosen == null || chosenEstimate == null || bestEstimate == null || source == null) {
            throw new IllegalArgumentException("decision fields must not be null");
        }
        if (candidates == null || estimates == null) {
            throw new IllegalArgumentException("candidates and estimates must not be null");
        }
        candidates = List.copyOf(candidates);
        estimates = List.copyOf(estimates);
        consultations = List.copyOf(consultations);
    }
}
