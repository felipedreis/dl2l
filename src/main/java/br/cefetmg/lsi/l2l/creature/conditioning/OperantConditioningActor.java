package br.cefetmg.lsi.l2l.creature.conditioning;

import br.cefetmg.lsi.l2l.creature.common.ActionType;
import br.cefetmg.lsi.l2l.world.FruitType;
import br.cefetmg.lsi.l2l.world.WorldObjectType;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Created by felipe on 23/08/17.
 */
public class OperantConditioningActor implements OperantConditioning {

    private List<ProbabilityBasedExperience> experiences;

    public OperantConditioningActor() {
        experiences = new ArrayList<>();
        experiences.add(new ProbabilityBasedExperience(FruitType.GRAY_APPLE));
        experiences.add(new ProbabilityBasedExperience(FruitType.RED_APPLE));
        experiences.add(new ProbabilityBasedExperience(FruitType.GREEN_APPLE));
    }

    @Override
    public void varyProbability(WorldObjectType target, ActionType action, double delta, boolean valence) {
        ProbabilityBasedExperience experience = experiences.stream()
                .filter(e -> e.target.equals(target))
                .findAny()
                .orElseThrow(IllegalArgumentException::new);

        ActionProbability actionProbability = experience.actionsProbability
                .stream()
                .filter(a -> a.getAction().equals(action))
                .findAny()
                .orElseThrow(() -> {
                    return new IllegalArgumentException("Unknown action " + action + " for target " + target);
                });

        if(!valence)
            delta = -delta;
        double actionsDelta = -delta / (experience.actionsProbability.size() - 1d);

        actionProbability.varyProbability(delta);
        experience.actionsProbability.stream()
                .filter(ap -> ap != actionProbability)
                .forEach(ap -> ap.varyProbability(actionsDelta));
    }

    @Override
    public Optional<List<ActionProbability>> getProbabilities(WorldObjectType target) {
        return experiences.stream()
                .filter(e -> e.target.equals(target))
                .map(e -> e.actionsProbability)
                .findAny();
    }
}
