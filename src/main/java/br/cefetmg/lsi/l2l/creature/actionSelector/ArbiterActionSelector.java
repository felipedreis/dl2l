package br.cefetmg.lsi.l2l.creature.actionSelector;

import br.cefetmg.lsi.l2l.creature.arbiter.Arbiter;
import br.cefetmg.lsi.l2l.creature.arbiter.ArbitrationDecision;
import br.cefetmg.lsi.l2l.creature.arbiter.Estimate;
import br.cefetmg.lsi.l2l.creature.bd.ActionSelectionType;
import br.cefetmg.lsi.l2l.creature.common.Action;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The arbiter as an action selector (issue #104). The pre-filters that still apply run first, then the
 * {@link Arbiter} chooses among what is left.
 *
 * <p>Unlike {@link FilterBasedActionSelector}, the arbiter always runs, even on a single candidate. A
 * filter chain stops as soon as one candidate is left, so the arbiter would then never be asked and the
 * Q of the executed action would stay unknown; the episode buffer needs that Q for the pre-emption
 * bootstrap. With one candidate the arbiter returns it without a random draw and still estimates it.
 *
 * <p>Holds no cycle state: the inputs of each cycle arrive in the {@link SelectionContext}. The one
 * thing kept between calls is the last decision, which {@link #takeLastDecision()} hands out once.
 */
public class ArbiterActionSelector implements ActionSelector {

    private final List<ActionFilter> preFilters;
    private final Arbiter arbiter;
    private ArbitrationDecision lastDecision;

    /**
     * @param preFilters the pre-filters, in order; they only narrow the candidates
     * @param arbiter    the decision core; owned by this selector and not shared
     */
    public ArbiterActionSelector(List<ActionFilter> preFilters, Arbiter arbiter) {
        if (preFilters == null || arbiter == null) {
            throw new IllegalArgumentException("preFilters and arbiter must not be null");
        }
        this.preFilters = List.copyOf(preFilters);
        this.arbiter = arbiter;
    }

    @Override
    public Action selectOne(List<Action> candidates, SelectionContext ctx) {
        List<Action> narrowed = new ArrayList<>(candidates);
        for (ActionFilter filter : preFilters) {
            if (narrowed.size() == 1) {
                break;
            }
            narrowed = filter.filter(narrowed, ctx.toRegulate());
        }
        lastDecision = arbiter.decide(narrowed, ctx.h(), ctx.focalDrive(), ctx.efficiency());
        return lastDecision.chosen();
    }

    /** The decision of the last {@link #selectOne}, returned once: a second call returns empty until the next selection. */
    public Optional<ArbitrationDecision> takeLastDecision() {
        Optional<ArbitrationDecision> out = Optional.ofNullable(lastDecision);
        lastDecision = null;
        return out;
    }

    /**
     * The estimate of one action, in the context's focal drive. Asks the arbiter about this single action: a lone
     * candidate is decided by the first controller consulted (its decisiveness is 1, which reaches any theta; in
     * fixed-order mode, by the same positional rule) and draws no random number, so this does not disturb the
     * arbiter's stream.
     */
    public Estimate valueOf(Action executed, SelectionContext ctx) {
        return arbiter.decide(List.of(executed), ctx.h(), ctx.focalDrive(), ctx.efficiency()).chosenEstimate();
    }

    @Override
    public ActionSelectionType lastSelectionType() {
        return ActionSelectionType.ARBITER;
    }
}
