package br.cefetmg.lsi.l2l.creature.actionSelector;

import br.cefetmg.lsi.l2l.creature.bd.ActionSelectionType;
import br.cefetmg.lsi.l2l.creature.common.Action;
import br.cefetmg.lsi.l2l.creature.components.Emotion;

import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * Created by felipe on 23/08/17.
 */
public class RandomFilter implements ActionFilter{
    Random random;

    public RandomFilter(){
        random = new Random(System.currentTimeMillis());
    }

    @Override
    public List<Action> filter(List<Action> action, Emotion toRegulate) {
        return Arrays.asList(action.get(random.nextInt(action.size())));
    }

    @Override
    public ActionSelectionType getFilterType() {
        return ActionSelectionType.RANDOM;
    }
}
