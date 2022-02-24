package br.cefetmg.lsi.l2l.creature.actionSelector;

import br.cefetmg.lsi.l2l.creature.bd.ActionSelectionType;
import br.cefetmg.lsi.l2l.creature.common.Action;
import br.cefetmg.lsi.l2l.creature.common.ActionType;
import br.cefetmg.lsi.l2l.creature.components.Emotion;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * Created by felipe on 18/03/17.
 */
public class ActionSelection {

    private List<ActionFilter> filters;

    private ActionFilter lastUsedFilter;

    public ActionSelection(){
        filters = new ArrayList<>();
    }

    public ActionSelection(ActionFilter ... filters) {
        this.filters = Arrays.asList(filters);
    }

    public Action selectOne(List<Action> actions, Emotion toRegulate) {

        List<Action> filtered = new ArrayList<>(actions);

        for(ActionFilter filter : filters) {
            lastUsedFilter = filter;
            filtered = filter.filter(filtered, toRegulate);

            if(filtered.size() == 1)
                break;
        }

        return filtered.get(0);
    }

    public ActionSelectionType getLastUsedFilterType() {
        return lastUsedFilter.getFilterType();
    }
}
