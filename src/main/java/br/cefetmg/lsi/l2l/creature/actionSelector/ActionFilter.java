package br.cefetmg.lsi.l2l.creature.actionSelector;

import br.cefetmg.lsi.l2l.creature.bd.ActionSelectionType;
import br.cefetmg.lsi.l2l.creature.common.Action;
import br.cefetmg.lsi.l2l.creature.components.Emotion;

import java.util.List;

/**
 * Created by felipe on 23/08/17.
 */
public interface ActionFilter {
    List<Action> filter(List<Action> action, Emotion toRegulate);

    ActionSelectionType getFilterType();
}
