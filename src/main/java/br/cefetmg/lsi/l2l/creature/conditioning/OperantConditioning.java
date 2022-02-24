package br.cefetmg.lsi.l2l.creature.conditioning;

import br.cefetmg.lsi.l2l.creature.common.ActionType;
import br.cefetmg.lsi.l2l.world.WorldObjectType;

import java.util.List;
import java.util.Optional;

/**
 * Created by felipe on 23/08/17.
 */
public interface OperantConditioning {
    void varyProbability(WorldObjectType target, ActionType action, double delta, boolean valence);

    Optional<List<ActionProbability>> getProbabilities(WorldObjectType target);

}
