package br.cefetmg.lsi.l2l.creature.actionSelector;

import br.cefetmg.lsi.l2l.creature.bd.ActionSelectionType;
import br.cefetmg.lsi.l2l.creature.common.Action;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The legacy selection mechanism: the candidates pass through an ordered chain of {@link ActionFilter}s,
 * and the chain stops as soon as one filter leaves a single candidate (issue #104 renamed it from
 * {@code ActionSelection}; the loop is unchanged).
 *
 * <p>Reads only {@link SelectionContext#toRegulate()}: the focal drive, drive levels and efficiency are
 * for the arbiter.
 *
 * Created by felipe on 18/03/17.
 */
public class FilterBasedActionSelector implements ActionSelector {

    private List<ActionFilter> filters;

    private ActionFilter lastUsedFilter;

    public FilterBasedActionSelector(){
        filters = new ArrayList<>();
    }

    public FilterBasedActionSelector(ActionFilter ... filters) {
        this.filters = Arrays.asList(filters);
    }

    public FilterBasedActionSelector(List<ActionFilter> filters) {
        this.filters = new ArrayList<>(filters);
    }

    @Override
    public Action selectOne(List<Action> actions, SelectionContext ctx) {

        List<Action> filtered = new ArrayList<>(actions);

        for(ActionFilter filter : filters) {
            lastUsedFilter = filter;
            filtered = filter.filter(filtered, ctx.toRegulate());

            if(filtered.size() == 1)
                break;
        }

        return filtered.get(0);
    }

    @Override
    public ActionSelectionType lastSelectionType() {
        return lastUsedFilter.getFilterType();
    }
}
