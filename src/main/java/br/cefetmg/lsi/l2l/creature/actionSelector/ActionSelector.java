package br.cefetmg.lsi.l2l.creature.actionSelector;

import br.cefetmg.lsi.l2l.creature.bd.ActionSelectionType;
import br.cefetmg.lsi.l2l.creature.common.Action;

import java.util.List;

/**
 * The step that turns the cycle's candidate actions into the one action the creature takes.
 *
 * <p>Two mechanisms implement it: the legacy filter chain ({@link FilterBasedActionSelector}) and the
 * arbiter ({@link ArbiterActionSelector}, issue #104). {@code FullAppraisal} holds one of them and
 * chooses which by {@code LearningSettings.actionSelector}; nothing else depends on which.
 *
 * <p>Per-cycle inputs travel in the {@link SelectionContext} argument, not as setters, so a selector
 * holds no cycle state between calls.
 */
public interface ActionSelector {

    /**
     * @param candidates the candidate actions of this cycle; never empty
     * @param ctx        the inputs of this cycle
     * @return the action to take; one of {@code candidates}
     */
    Action selectOne(List<Action> candidates, SelectionContext ctx);

    /** The type persisted in {@code ChosenActionState} for the decision of the last {@link #selectOne}. */
    ActionSelectionType lastSelectionType();
}
