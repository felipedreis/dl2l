package br.cefetmg.lsi.l2l.creature.actionSelector;

import br.cefetmg.lsi.l2l.common.Constants;
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
 *
 * <p>Probabilistic (striatal) action chooser. Tonic neuromodulators bias the sampling distribution
 * at selection time via {@link #setModulation(double, double)} — dopamine raises the softmax
 * temperature (exploration), serotonin up-weights quieting actions (rest/observe/wander). The
 * learned operant table is never modified by this bias; the default state (temperature 1, no bias)
 * reproduces the pre-neuromodulation behaviour exactly.
 */
public class ActionProbabilityFilter implements ActionFilter {
    private static final Set<ActionType> QUIETING_ACTIONS =
            EnumSet.of(ActionType.SLEEP, ActionType.OBSERVE, ActionType.WANDER);

    private Random random;
    private OperantConditioning operantConditioning;

    private double temperature = 1.0;   // softmax temperature; 1.0 = unmodulated
    private double restBias = 0.0;       // additive up-weight fraction for quieting actions; 0.0 = none

    public ActionProbabilityFilter(OperantConditioning operantConditioning) {
        // No-arg Random() self-seeds from an atomic uniquifier ^ nanoTime, so filters created in the
        // same millisecond are not correlated (unlike seeding with currentTimeMillis).
        this(operantConditioning, new Random());
    }

    /** Seedable constructor for deterministic tests of the sampling distribution. */
    ActionProbabilityFilter(OperantConditioning operantConditioning, Random random) {
        this.operantConditioning = operantConditioning;
        this.random = random;
    }

    /**
     * Set the tonic neuromodulator gains for the next selection. {@code daTonic} raises the
     * temperature (flatter distribution → exploration); {@code serotoninTonic} raises the rest bias.
     * Serotonin is de-scaled by the pool's leak so the effective term is the underlying satiety ∈ [0,1].
     */
    public void setModulation(double daTonic, double serotoninTonic) {
        this.temperature = 1.0 + Constants.DA_EXPLORATION_GAIN * Math.tanh(Math.max(0.0, daTonic));
        double satiety = clamp01(serotoninTonic * (1.0 - Constants.SEROTONIN_DECAY));
        this.restBias = Constants.SEROTONIN_REST_GAIN * satiety;
    }

    private static double clamp01(double v) {
        return v < 0 ? 0 : (v > 1 ? 1 : v);
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

                    // Tonic dopamine: soften/sharpen via temperature (p^(1/T)); T=1 leaves it unchanged.
                    if (temperature != 1.0) {
                        probability = Math.pow(probability, 1.0 / temperature);
                    }
                    // Tonic serotonin: up-weight quieting actions (rest/observe/wander).
                    if (restBias != 0.0 && QUIETING_ACTIONS.contains(actToDisambiguate.type)) {
                        probability *= (1.0 + restBias);
                    }

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
