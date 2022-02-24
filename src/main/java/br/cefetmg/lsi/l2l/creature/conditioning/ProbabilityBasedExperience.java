package br.cefetmg.lsi.l2l.creature.conditioning;

import br.cefetmg.lsi.l2l.creature.common.ActionType;
import br.cefetmg.lsi.l2l.world.WorldObjectType;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Created by felipe on 23/08/17.
 */
public class ProbabilityBasedExperience implements Serializable{
    public final WorldObjectType target;
    public final List<ActionProbability> actionsProbability;
    private int experience;

    public ProbabilityBasedExperience(WorldObjectType target) {

        this.target = target;
        actionsProbability = new ArrayList<>();
        experience = 0;

        this.actionsProbability.add(new ActionProbability(ActionType.APPROACH,25)) ;
        this.actionsProbability.add(new ActionProbability(ActionType.AVOID, 25));
        this.actionsProbability.add(new ActionProbability(ActionType.EAT, 25));
        this.actionsProbability.add(new ActionProbability(ActionType.SLEEP, 25));
        this.actionsProbability.add(new ActionProbability(ActionType.TOUCH, 0));
        this.actionsProbability.add(new ActionProbability(ActionType.PLAY, 0));

    }

    public ProbabilityBasedExperience(WorldObjectType target, ActionProbability ... probabilities) {
        this.target = target;
        actionsProbability = new ArrayList<>();

        actionsProbability.addAll(Arrays.asList(probabilities));
    }

    public int getExperience() {
        return experience;
    }

    public void setExperience(int experience) {
        this.experience = experience;
    }

    public void incrementExperience(){
        this.experience++;
    }
}
