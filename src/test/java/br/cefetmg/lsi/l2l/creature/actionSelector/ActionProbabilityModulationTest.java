package br.cefetmg.lsi.l2l.creature.actionSelector;

import br.cefetmg.lsi.l2l.common.SequentialId;
import br.cefetmg.lsi.l2l.creature.common.Action;
import br.cefetmg.lsi.l2l.creature.common.ActionType;
import br.cefetmg.lsi.l2l.creature.common.Perception;
import br.cefetmg.lsi.l2l.creature.components.Emotion;
import br.cefetmg.lsi.l2l.creature.conditioning.ActionProbability;
import br.cefetmg.lsi.l2l.creature.conditioning.OperantConditioning;
import br.cefetmg.lsi.l2l.world.FruitType;
import br.cefetmg.lsi.l2l.world.WorldObjectType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that tonic neuromodulators bias the affordance sampling distribution without touching the
 * learned operant table: dopamine flattens it (exploration), serotonin up-weights quieting actions.
 */
public class ActionProbabilityModulationTest {

    private static final Emotion HUNGER = new Emotion("hunger");
    private static final Perception APPLE = new Perception(FruitType.RED_APPLE, new SequentialId(1), 0, 0);

    // Operant table stub: EAT strongly favoured (80) over the quieting WANDER (20).
    private static OperantConditioning stub() {
        return new OperantConditioning() {
            @Override public void varyProbability(WorldObjectType t, ActionType a, double d, boolean v) { }
            @Override public Optional<List<ActionProbability>> getProbabilities(WorldObjectType target) {
                return Optional.of(List.of(
                        new ActionProbability(ActionType.EAT, 80),
                        new ActionProbability(ActionType.WANDER, 20)));
            }
        };
    }

    private static double wanderShare(ActionProbabilityFilter filter) {
        int wander = 0;
        int n = 40000;
        for (int i = 0; i < n; i++) {
            List<Action> candidates = new java.util.ArrayList<>(List.of(
                    new Action(ActionType.EAT, APPLE), new Action(ActionType.WANDER, APPLE)));
            Action chosen = filter.filter(candidates, HUNGER).get(0);
            if (chosen.type == ActionType.WANDER) wander++;
        }
        return wander / (double) n;
    }

    @Test
    void baseline_share_matches_operant_probabilities() {
        ActionProbabilityFilter filter = new ActionProbabilityFilter(stub(), new Random(42));
        assertEquals(0.20, wanderShare(filter), 0.02);
    }

    @Test
    void serotonin_rest_bias_raises_quieting_action_share() {
        ActionProbabilityFilter filter = new ActionProbabilityFilter(stub(), new Random(42));
        // serotoninTonic=20 → de-scaled satiety≈1 → restBias≈1 → WANDER weight doubles: 40/120≈0.33.
        filter.setModulation(0.0, 20.0);
        assertEquals(0.333, wanderShare(filter), 0.02);
    }

    @Test
    void dopamine_temperature_flattens_the_distribution() {
        ActionProbabilityFilter filter = new ActionProbabilityFilter(stub(), new Random(42));
        // daTonic large → T≈3 → EAT 80^(1/3)=4.31 vs WANDER 20^(1/3)=2.71 → share≈0.386.
        filter.setModulation(3.0, 0.0);
        double share = wanderShare(filter);
        assertTrue(share > 0.30 && share < 0.45,
                "temperature should flatten toward parity, got " + share);
    }

    @Test
    void default_state_is_unmodulated() {
        // Without any setModulation call, behaviour equals baseline.
        ActionProbabilityFilter filter = new ActionProbabilityFilter(stub(), new Random(7));
        assertEquals(0.20, wanderShare(filter), 0.02);
    }
}
