package br.cefetmg.lsi.l2l.creature.actionSelector;

import br.cefetmg.lsi.l2l.creature.bd.ActionSelectionType;
import br.cefetmg.lsi.l2l.creature.common.Action;
import br.cefetmg.lsi.l2l.creature.components.Emotion;

import java.util.List;
import java.util.Map;

/**
 * Created by felipe on 19/10/17.
 */
public class TargetDistanceFilter implements ActionFilter {

    public TargetDistanceFilter(){}

    @Override
    public List<Action> filter(List<Action> acts, Emotion toRegulate) {

        for (int i = 0; i < acts.size(); ++i) {

            Action ithAct = acts.get(i);

            for (int j = i + 1; j < acts.size(); ++j) {

                Action jthAct = acts.get(j);

                if (ithAct.getTarget() == null || jthAct.getTarget() == null)
                    continue;

                if (ithAct.getTarget().equals(jthAct.getTarget()) &&
                        ithAct.type.equals(jthAct.type)) {

                    if (ithAct.perception.distance <= jthAct.perception.distance) {
                        acts.remove(j);
                        j--;
                    } else {
                        acts.remove(i);
                        i--;
                        break;
                    }
                }
            }
        }

        return acts;
    }

    @Override
    public ActionSelectionType getFilterType() {
        return ActionSelectionType.TARGET_DISTANCE;
    }
}
