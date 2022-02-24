package br.cefetmg.lsi.l2l.creature.actionSelector;

import br.cefetmg.lsi.l2l.creature.bd.ActionSelectionType;
import br.cefetmg.lsi.l2l.creature.common.Action;
import br.cefetmg.lsi.l2l.creature.common.ActionType;
import br.cefetmg.lsi.l2l.creature.components.Emotion;
import br.cefetmg.lsi.l2l.creature.conditioning.ActionProbability;
import br.cefetmg.lsi.l2l.creature.conditioning.OperantConditioning;
import br.cefetmg.lsi.l2l.creature.conditioning.ProbabilityBasedExperience;
import br.cefetmg.lsi.l2l.world.WorldObject;
import br.cefetmg.lsi.l2l.world.WorldObjectType;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Created by felipe on 24/08/17.
 */
public class ActionProbabilityFilter implements ActionFilter {
    private Random random;
    private OperantConditioning operantConditioning;

    public ActionProbabilityFilter(OperantConditioning operantConditioning) {
        this.operantConditioning = operantConditioning;
        this.random = new Random(System.currentTimeMillis());
    }

    @Override
    public List<Action> filter(List<Action> actions, Emotion toRegulate) {
        Map<WorldObjectType, List<Action>> groupedActs = new HashMap<>();
        List<Action> disambiguatedActs = new ArrayList<>();

        for(Action act : actions) {

            WorldObjectType target = act.getTarget();

            if(!groupedActs.containsKey(target)) {
                groupedActs.put(act.getTarget(), new ArrayList<Action>());
            }

            groupedActs.get(target).add(act);
        }

        Iterator<WorldObjectType> targetsIt = groupedActs.keySet().iterator();

        while(targetsIt.hasNext()) {

            WorldObjectType target = targetsIt.next();

            List<Action> actsToDisambiguate = groupedActs.get(target);

            Optional<List<ActionProbability>> actionsProbabilityOp = operantConditioning.getProbabilities(target);
            if (actionsProbabilityOp.isPresent()) {
                List<ActionProbability> actionsProbability = actionsProbabilityOp.get();

                int numberOfActions = actsToDisambiguate.size();

                double [] probabilities = new double[actsToDisambiguate.size()];
                double sumOfProbabilities = 0;

                for (int i = 0; i < numberOfActions; ++i) {
                    Action actToDisambiguate = actsToDisambiguate.get(i);

                    double probability = getActionProbability(actionsProbability,
                            actToDisambiguate.type);

                    probabilities[i] = probability;
                    sumOfProbabilities += probability;
                }

                for (int j = 0; j < numberOfActions; ++j) {
                    probabilities[j] /= sumOfProbabilities;
                }

                double probability = random.nextDouble();
                double accumulator = 0;
                int chosenAction = 0;

                for (int k = 0; k < numberOfActions; ++k) {
                    accumulator += probabilities[k];

                    if (probability <= accumulator) {
                        chosenAction = k;
                        break;
                    }
                }

                Action disambiguatedAction = actsToDisambiguate.get(chosenAction);
                disambiguatedActs.add(disambiguatedAction);
            } else {
                disambiguatedActs.addAll(actsToDisambiguate);
            }
        }



        return disambiguatedActs;
    }

    /**
     * Method to find a probability of action.
     *
     * @param actionsProbabilities list of probabilities (encapsulated by ActionProbability object)
     * @param action act to find
     * @return probability of an action occur.
     */
    private double getActionProbability(List<ActionProbability> actionsProbabilities, ActionType action) {

        Iterator<ActionProbability> it = actionsProbabilities.iterator();

        while(it.hasNext()) {
            ActionProbability t = it.next();

            if(t.getAction().equals(action)) {
                return t.getProbability();
            }
        }

        return 0;
    }

    @Override
    public ActionSelectionType getFilterType() {
        return ActionSelectionType.AFFORDANCE;
    }
}
