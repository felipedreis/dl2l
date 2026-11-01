package br.cefetmg.lsi.l2l.creature.arbiter;

import br.cefetmg.lsi.l2l.creature.common.Action;

import java.util.List;

/**
 * What the {@link Arbiter} did on one decision: the controllers it consulted and how decisive each was,
 * the one whose estimates it drew from, the action it chose and how it was chosen.
 *
 * <p>Built now so the decision telemetry (#105) is plumbing only. It is not persisted by this issue.
 * Immutable: the lists are copied on the way in.
 *
 * @param candidateCount     number of candidates the arbiter chose among
 * @param candidates         the candidates, in the order the arbiter was given them
 * @param estimates          the deciding controller's estimate of each candidate, in the same order; this is
 *                           what lets the caller read the Q of an action other than the chosen one (the
 *                           hysteresis gate can replace the choice)
 * @param consultations      the controllers consulted, cheapest first, with each one's decisiveness
 * @param decidingController the controller whose estimates were drawn from and reported
 * @param chosenIndex        index of the chosen action in the candidate list
 * @param chosen             the chosen action
 * @param chosenEstimate     the deciding controller's estimate of the chosen action
 * @param runnerUpEstimate   the deciding controller's estimate of the candidate with the second-highest
 *                           Q; {@code null} when there is one candidate
 * @param source             how the action was picked
 */
public record ArbitrationDecision(int candidateCount,
                                  List<Action> candidates,
                                  List<Estimate> estimates,
                                  List<Consultation> consultations,
                                  Arbiter.ControllerType decidingController,
                                  int chosenIndex,
                                  Action chosen,
                                  Estimate chosenEstimate,
                                  Estimate runnerUpEstimate,
                                  ExplorationSource source) {

    /**
     * One controller's answer to the stopping rule.
     *
     * @param controller the controller consulted
     * @param conf       its decisiveness, in [0.5, 1]
     */
    public record Consultation(Arbiter.ControllerType controller, double conf) {
    }

    /**
     * How the chosen action was picked. {@code SINGLE}: one candidate, nothing to choose among.
     * {@code EPSILON}: the uniform floor. {@code GREEDY}: the Thompson draw happened to be the highest Q.
     * {@code THOMPSON}: the Thompson draw picked a candidate other than the highest Q.
     */
    public enum ExplorationSource { SINGLE, GREEDY, THOMPSON, EPSILON }

    public ArbitrationDecision {
        if (consultations == null || decidingController == null || chosen == null
                || chosenEstimate == null || source == null) {
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
