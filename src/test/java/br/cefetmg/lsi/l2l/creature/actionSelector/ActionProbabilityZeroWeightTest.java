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

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression test for the all-zero-weight fallback of the affordance filter (it used to return the first candidate).
 * What the affordance filter does when every candidate action of a target has weight 0 in the learned table.
 * This is the table the p107 gate-1 run learned for red and green apples: EAT = 1, every other action = 0 (only EAT is ever
 * reinforced). With EAT not among the candidates (the apple is seen but not in contact), all weights are 0, the
 * normalisation divides 0 by 0, and the draw {@code r <= NaN} is always false.
 */
public class ActionProbabilityZeroWeightTest {

    private static final Emotion HUNGER = new Emotion("hunger");
    private static final Perception APPLE = new Perception(FruitType.RED_APPLE, new SequentialId(1), 0, 0);

    /** The learned red-apple table of the p107 gate: EAT 1, everything else 0. */
    private static OperantConditioning learnedRedAppleTable() {
        return new OperantConditioning() {
            @Override public void varyProbability(WorldObjectType t, ActionType a, double d, boolean v) { }
            @Override public Optional<List<ActionProbability>> getProbabilities(WorldObjectType target) {
                return Optional.of(List.of(
                        new ActionProbability(ActionType.EAT, 1.0),
                        new ActionProbability(ActionType.APPROACH, 0.0),
                        new ActionProbability(ActionType.AVOID, 0.0),
                        new ActionProbability(ActionType.SLEEP, 0.0)));
            }
        };
    }

    private static Map<ActionType, Integer> picks(ActionType first, ActionType second, long seed) {
        ActionProbabilityFilter filter = new ActionProbabilityFilter(learnedRedAppleTable(), new Random(seed));
        Map<ActionType, Integer> count = new EnumMap<>(ActionType.class);
        for (int i = 0; i < 2000; i++) {
            List<Action> candidates = new ArrayList<>(List.of(new Action(first, APPLE), new Action(second, APPLE)));
            count.merge(filter.filter(candidates, HUNGER).get(0).type, 1, Integer::sum);
        }
        return count;
    }

    @Test
    void a_positive_weight_still_wins_over_zero_weights() {
        ActionProbabilityFilter filter = new ActionProbabilityFilter(learnedRedAppleTable(), new Random(3));
        for (int i = 0; i < 200; i++) {
            List<Action> candidates = new ArrayList<>(List.of(new Action(ActionType.APPROACH, APPLE),
                    new Action(ActionType.EAT, APPLE), new Action(ActionType.WANDER, APPLE)));
            assertTrue(filter.filter(candidates, HUNGER).get(0).type == ActionType.EAT);
        }
    }

    @Test
    void all_zero_weights_do_not_make_the_choice_depend_on_the_candidate_order() {
        Map<ActionType, Integer> approachFirst = picks(ActionType.APPROACH, ActionType.WANDER, 1);
        Map<ActionType, Integer> wanderFirst = picks(ActionType.WANDER, ActionType.APPROACH, 1);
        System.out.println("[order APPROACH,WANDER] " + approachFirst + "   [order WANDER,APPROACH] " + wanderFirst);

        int approachShareA = approachFirst.getOrDefault(ActionType.APPROACH, 0);
        int approachShareB = wanderFirst.getOrDefault(ActionType.APPROACH, 0);
        // A fair fallback gives about 1000 of 2000 in both orders; the old first-candidate fallback gave 2000 and 0.
        assertTrue(Math.abs(approachShareA - 1000) < 120 && Math.abs(approachShareB - 1000) < 120,
                "the pick follows the candidate order: APPROACH chosen " + approachShareA + "/2000 when first, "
                        + approachShareB + "/2000 when second");
    }
}
