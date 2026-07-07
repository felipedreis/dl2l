package br.cefetmg.lsi.l2l.creature.actionSelector;

import br.cefetmg.lsi.l2l.cluster.settings.LearningSettings;
import br.cefetmg.lsi.l2l.common.Constants;
import br.cefetmg.lsi.l2l.common.SequentialId;
import br.cefetmg.lsi.l2l.creature.bd.ActionSelectionType;
import br.cefetmg.lsi.l2l.creature.common.Action;
import br.cefetmg.lsi.l2l.creature.common.ActionType;
import br.cefetmg.lsi.l2l.creature.common.Perception;
import br.cefetmg.lsi.l2l.creature.components.Emotion;
import br.cefetmg.lsi.l2l.world.FruitType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the innate emotion→action coupling filter.
 */
public class ActionTendencyFilterTest {

    private static final Perception APPLE = new Perception(FruitType.RED_APPLE, new SequentialId(1), 10, 0);

    private static Emotion emotion(String name) {
        return new Emotion(name);
    }

    private static ActionTendencyFilter filter() {
        return new ActionTendencyFilter(LearningSettings.DEFAULT_ACTION_TENDENCIES);
    }

    private static List<Action> actions(ActionType... types) {
        return java.util.Arrays.stream(types).map(t -> new Action(t, APPLE)).collect(Collectors.toList());
    }

    private static List<ActionType> types(List<Action> as) {
        return as.stream().map(a -> a.type).collect(Collectors.toList());
    }

    @Test
    void hunger_keeps_only_foraging_actions_and_drops_sleep() {
        // Candidates at a fruit: APPROACH/AVOID/SLEEP/OBSERVE. Under HUNGER only APPROACH survives
        // (EAT/APPROACH/WANDER are the hunger tendency; AVOID/SLEEP/OBSERVE are not).
        List<Action> result = filter().filter(
                actions(ActionType.APPROACH, ActionType.AVOID, ActionType.SLEEP, ActionType.OBSERVE),
                emotion(Constants.HUNGER));

        assertEquals(List.of(ActionType.APPROACH), types(result));
        assertFalse(types(result).contains(ActionType.SLEEP), "a hungry creature must not consider SLEEP");
    }

    @Test
    void sleep_keeps_sleep_and_wander() {
        List<Action> result = filter().filter(
                actions(ActionType.APPROACH, ActionType.SLEEP, ActionType.WANDER),
                emotion(Constants.SLEEP));

        assertEquals(List.of(ActionType.SLEEP, ActionType.WANDER), types(result));
    }

    @Test
    void empty_intersection_passes_through_unchanged() {
        // Under HUNGER but candidates are only AVOID/SLEEP (neither in the hunger tendency) →
        // pass-through, never starve the pipeline.
        List<Action> input = actions(ActionType.AVOID, ActionType.SLEEP);
        List<Action> result = filter().filter(input, emotion(Constants.HUNGER));

        assertEquals(types(input), types(result));
    }

    @Test
    void null_emotion_passes_through() {
        List<Action> input = actions(ActionType.APPROACH, ActionType.SLEEP);
        assertEquals(types(input), types(filter().filter(input, null)));
    }

    @Test
    void unknown_emotion_passes_through() {
        List<Action> input = actions(ActionType.APPROACH, ActionType.SLEEP);
        assertEquals(types(input), types(filter().filter(input, emotion("fear"))));
    }

    @Test
    void filter_type_is_action_tendency() {
        assertEquals(ActionSelectionType.ACTION_TENDENCY, filter().getFilterType());
    }
}
