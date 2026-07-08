package br.cefetmg.lsi.l2l.creature.actionSelector;

import br.cefetmg.lsi.l2l.creature.bd.ActionSelectionType;
import br.cefetmg.lsi.l2l.creature.common.Action;
import br.cefetmg.lsi.l2l.creature.common.ActionType;
import br.cefetmg.lsi.l2l.creature.components.Emotion;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Innate emotion→action coupling (Campos 2006 action tendencies): each emotion carries a coarse set
 * of actions that regulate it. This filter keeps only the candidate actions that lie in the dominant
 * drive's tendency set — so, e.g., a hungry creature no longer considers SLEEP and instead pursues
 * EAT/APPROACH/WANDER. It is a <em>soft</em> first filter: when the intersection with the candidate
 * list is empty it passes the list through unchanged (the 2015 robustness rule — never starve the
 * pipeline). The fine choice within the surviving set is still made by the learned filters downstream.
 */
public class ActionTendencyFilter implements ActionFilter {

    private final Map<String, Set<ActionType>> tendencyByEmotion;

    public ActionTendencyFilter(Map<String, Set<ActionType>> tendencyByEmotion) {
        this.tendencyByEmotion = tendencyByEmotion;
    }

    @Override
    public List<Action> filter(List<Action> actions, Emotion toRegulate) {
        if (toRegulate == null) return actions;

        Set<ActionType> tendency = tendencyByEmotion.get(toRegulate.getName());
        if (tendency == null || tendency.isEmpty()) return actions;

        List<Action> preferred = actions.stream()
                .filter(a -> tendency.contains(a.type))
                .collect(Collectors.toList());

        // Pass-through when nothing matches — never starve the downstream filters.
        return preferred.isEmpty() ? actions : preferred;
    }

    @Override
    public ActionSelectionType getFilterType() {
        return ActionSelectionType.ACTION_TENDENCY;
    }
}
