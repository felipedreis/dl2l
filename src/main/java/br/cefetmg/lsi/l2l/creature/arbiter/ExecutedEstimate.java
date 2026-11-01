package br.cefetmg.lsi.l2l.creature.arbiter;

import br.cefetmg.lsi.l2l.creature.common.Action;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * The estimates of the action that was actually executed (issue #105). The hysteresis gate can replace the
 * arbiter's pick, and the pre-emption bootstrap and the calibration both need the Q of what the creature did,
 * not of what the arbiter preferred. This record resolves that once, from the decision already in hand, so the
 * episode buffer's {@code chosenQ} and the arbitration decision row read the same number.
 *
 * <p>Immutable: the per-controller map is copied.
 *
 * @param action       the executed action
 * @param source       where its estimate came from: the pick, another candidate, or a fresh estimate
 * @param deciding     the deciding controller's estimate of the executed action
 * @param byController each consulted controller's estimate of the executed action; for {@link Source#VALUE_OF}
 *                     only the cheapest controller's, which is the one that answered the fresh estimate
 */
public record ExecutedEstimate(Action action, Source source, Estimate deciding,
                               Map<Arbiter.ControllerType, Estimate> byController) {

    /** {@code CHOSEN}: the arbiter's pick. {@code OTHER_CANDIDATE}: another candidate (the gate's SLEEP, say). {@code VALUE_OF}: not a candidate, so asked afresh. */
    public enum Source { CHOSEN, OTHER_CANDIDATE, VALUE_OF }

    public ExecutedEstimate {
        if (action == null || source == null || deciding == null || byController == null) {
            throw new IllegalArgumentException("executed estimate fields must not be null");
        }
        byController = Map.copyOf(byController);
    }

    /**
     * Resolves the executed action against the decision. {@code valueOf} is called only when the executed action
     * is not one of the decision's candidates, which is the rare branch the old {@code executedQ} already had.
     *
     * @param d        the decision of this cycle; not null
     * @param executed the action the creature executes; not null
     * @param valueOf  a fresh estimate of one action, under the cheapest controller
     */
    public static ExecutedEstimate of(ArbitrationDecision d, Action executed, Function<Action, Estimate> valueOf) {
        if (d == null || executed == null || valueOf == null) {
            throw new IllegalArgumentException("decision, executed action and valueOf must not be null");
        }
        if (executed.equals(d.chosen())) {
            return fromConsultations(executed, Source.CHOSEN, d.chosenIndex(), d);
        }
        for (int i = 0; i < d.candidates().size(); i++) {
            if (d.candidates().get(i).equals(executed)) {
                return fromConsultations(executed, Source.OTHER_CANDIDATE, i, d);
            }
        }
        Estimate fresh = valueOf.apply(executed);
        Map<Arbiter.ControllerType, Estimate> cheapest = new HashMap<>();
        cheapest.put(d.consultations().get(0).controller(), fresh);
        return new ExecutedEstimate(executed, Source.VALUE_OF, fresh, cheapest);
    }

    private static ExecutedEstimate fromConsultations(Action executed, Source source, int index, ArbitrationDecision d) {
        Map<Arbiter.ControllerType, Estimate> byController = new HashMap<>();
        for (ArbitrationDecision.Consultation c : d.consultations()) {
            byController.put(c.controller(), c.estimates().get(index));
        }
        return new ExecutedEstimate(executed, source, d.estimates().get(index), byController);
    }
}
