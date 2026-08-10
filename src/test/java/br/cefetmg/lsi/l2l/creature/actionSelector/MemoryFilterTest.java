package br.cefetmg.lsi.l2l.creature.actionSelector;

import br.cefetmg.lsi.l2l.common.SequentialId;
import br.cefetmg.lsi.l2l.creature.bd.ActionSelectionType;
import br.cefetmg.lsi.l2l.creature.common.Action;
import br.cefetmg.lsi.l2l.creature.common.ActionType;
import br.cefetmg.lsi.l2l.creature.common.Perception;
import br.cefetmg.lsi.l2l.creature.components.Emotion;
import br.cefetmg.lsi.l2l.creature.memory.Engram;
import br.cefetmg.lsi.l2l.creature.memory.MemorySystem;
import br.cefetmg.lsi.l2l.creature.memory.ShortTermMemory;
import br.cefetmg.lsi.l2l.world.FruitType;
import br.cefetmg.lsi.l2l.world.WorldObjectType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for MemoryFilter.
 *
 * <p>Memory chooses the object and hands every action on it to the operant table; it does not
 * choose the action. The choice is a weighted draw, so the behavioural properties are frequencies
 * rather than single outcomes: each such test runs the filter {@link #DRAWS} times against a seeded
 * {@link Random} and asserts on the resulting share. Seeded, so a failure is reproducible.
 *
 * <p>Uses a stub MemorySystem backed by a simple list — no Akka cluster needed.
 */
public class MemoryFilterTest {

    /** Enough draws that a 5-percentage-point band around the expected share is not flaky. */
    private static final int DRAWS = 4000;
    private static final long SEED = 8_488L;

    private static final Emotion HUNGER = emotion("hunger", 5.0);
    private static final SequentialId RED_ID   = new SequentialId(1);
    private static final SequentialId GREEN_ID = new SequentialId(2);

    private static final Perception RED_NEAR   = new Perception(FruitType.RED_APPLE,   RED_ID,   10, 0);
    private static final Perception GREEN_NEAR = new Perception(FruitType.GREEN_APPLE, GREEN_ID, 10, 0);
    private static final Perception SELF_PERC  = new Perception(null, new SequentialId(3), 0, 0);

    private ListMemorySystem memory;

    @BeforeEach
    void setUp() {
        memory = new ListMemorySystem();
    }

    // -----------------------------------------------------------------------
    // Gate conditions — all pass the candidates through for a later filter
    // -----------------------------------------------------------------------

    @Test
    void empty_engrams_returns_all_actions_unchanged() {
        List<Action> result = filter().filter(actions(approach(RED_NEAR), approach(GREEN_NEAR)), HUNGER);

        assertEquals(2, result.size());
    }

    @Test
    void single_candidate_returns_unchanged_without_engrams() {
        List<Action> result = filter().filter(actions(approach(RED_NEAR)), HUNGER);

        assertEquals(1, result.size());
        assertEquals(ActionType.APPROACH, result.get(0).type);
    }

    @Test
    void single_candidate_returns_unchanged_with_engrams() {
        memory.add(engram(ActionType.APPROACH, FruitType.RED_APPLE, -2.0, 0.8));

        List<Action> result = filter().filter(actions(approach(RED_NEAR)), HUNGER);

        assertEquals(1, result.size());
    }

    @Test
    void several_actions_on_one_object_pass_through_for_the_operant_table_to_choose() {
        // The structural point of the redesign: memory says WHAT to engage with, operant
        // conditioning says WHAT TO DO to it (Mapa 2009 §5.3.2). With only one object in view
        // there is no object-level choice to make, so memory must not narrow the action set —
        // narrowing here is exactly what used to crowd out EAT.
        memory.add(engram(ActionType.APPROACH, FruitType.RED_APPLE, -2.0, 1.0));

        List<Action> result = filter().filter(actions(approach(RED_NEAR), eat(RED_NEAR)), HUNGER);

        assertEquals(2, result.size(), "memory must leave the action choice to the operant table");
    }

    @Test
    void no_evidence_about_any_visible_object_passes_through() {
        // Engrams exist, but only about an object type that is not in view.
        memory.add(engram(ActionType.EAT, FruitType.GRAY_APPLE, -1.0, 0.5));

        List<Action> result = filter().filter(actions(approach(RED_NEAR), approach(GREEN_NEAR)), HUNGER);

        assertEquals(2, result.size(),
                "with nothing known about either object, the operant table is better informed "
                        + "than a uniform draw would be");
    }

    // -----------------------------------------------------------------------
    // What memory returns
    // -----------------------------------------------------------------------

    @Test
    void every_action_on_the_chosen_object_is_returned() {
        // RED is remembered as good, GREEN as harmful. Whichever is drawn, ALL the candidate
        // actions on it must come back — memory narrows to an object, never to an action.
        memory.add(engram(ActionType.APPROACH, FruitType.RED_APPLE,   -2.0, 1.0));   // +2.0
        memory.add(engram(ActionType.APPROACH, FruitType.GREEN_APPLE, +1.0, 1.0));   // -1.0

        MemoryFilter filter = filter();
        for (int i = 0; i < DRAWS; i++) {
            List<Action> result = filter.filter(
                    actions(approach(RED_NEAR), eat(RED_NEAR), approach(GREEN_NEAR)), HUNGER);

            WorldObjectType chosen = result.get(0).getTarget();
            assertTrue(result.stream().allMatch(a -> a.getTarget() == chosen),
                    "the surviving actions must all target one object");
            assertEquals(chosen == FruitType.RED_APPLE ? 2 : 1, result.size(),
                    "every candidate action on the chosen object must survive");
        }
    }

    // -----------------------------------------------------------------------
    // How memory chooses — proportional, per Campos §III-C
    // -----------------------------------------------------------------------

    @Test
    void selection_frequency_is_proportional_to_remembered_value() {
        // Campos: "Actions with a positive value are selected with a probability proportional to
        // this value". RED 3.0 vs GREEN 1.0 → RED should win three draws in four.
        memory.add(engram(ActionType.APPROACH, FruitType.RED_APPLE,   -3.0, 1.0));
        memory.add(engram(ActionType.APPROACH, FruitType.GREEN_APPLE, -1.0, 1.0));

        double redShare = shareChoosing(FruitType.RED_APPLE);

        assertEquals(0.75, redShare, 0.05,
                "a three-times-better object should be chosen about three times as often, not "
                        + "always — an argmax here is what locks the creature into its own past "
                        + "choices");
    }

    @Test
    void an_unexplored_object_beats_one_remembered_as_harmful() {
        // Campos: "those with a negative value are not selected". We down-weight rather than
        // exclude — his rule is an absorbing state that can never be revised if the world changes
        // — but the ordering he specifies must still hold overwhelmingly.
        //
        // This inverts the old behaviour, which the test
        // `action_with_no_matching_engram_wins_only_when_all_scored_are_negative` pinned as
        // intended: "even negative scores get picked over no score".
        memory.add(engram(ActionType.APPROACH, FruitType.GREEN_APPLE, +1.0, 1.0));   // -1.0

        double redShare = shareChoosing(FruitType.RED_APPLE);   // RED has no engram at all

        assertTrue(redShare > 0.95,
                "an unexplored object must beat a remembered-harmful one; got " + redShare);
        assertTrue(redShare < 1.0,
                "but not with certainty — a punished object must keep a path back");
    }

    @Test
    void undefined_object_type_actions_are_grouped_as_one_object() {
        // WANDER/SLEEP have no target object; they share the null key and so stand or fall
        // together, with the operant table choosing between them if they are drawn.
        memory.add(engram(ActionType.WANDER,   null,                 -5.0, 1.0));    // +5.0
        memory.add(engram(ActionType.APPROACH, FruitType.RED_APPLE,  +1.0, 1.0));    // -1.0

        Action wander = new Action(ActionType.WANDER, SELF_PERC);
        Action sleep  = new Action(ActionType.SLEEP,  SELF_PERC);

        MemoryFilter filter = filter();
        int bothReturned = 0;
        for (int i = 0; i < DRAWS; i++) {
            List<Action> result = filter.filter(actions(wander, sleep, approach(RED_NEAR)), HUNGER);
            if (result.size() == 2) {
                assertTrue(result.stream().allMatch(a -> a.getTarget() == null));
                bothReturned++;
            }
        }
        assertTrue(bothReturned > DRAWS * 0.9,
                "the self-directed actions are far better remembered and should usually win "
                        + "together; got " + bothReturned + "/" + DRAWS);
    }

    // -----------------------------------------------------------------------
    // Mean, not sum (issue #88) — now keyed per object rather than per (action, object)
    // -----------------------------------------------------------------------

    @Test
    void multiple_engrams_for_one_object_are_averaged_not_summed() {
        // RED: two engrams, +0.8 and +0.5 → sum 1.3, MEAN 0.65
        // GREEN: one engram, +1.2        → sum 1.2, MEAN 1.20
        //
        // Under a sum RED is preferred (1.3 > 1.2) purely for having been engaged more often,
        // though each engagement was worth less than GREEN's single one. Under the mean GREEN is
        // preferred, by 1.20 : 0.65.
        memory.add(engram(ActionType.APPROACH, FruitType.RED_APPLE,   -0.8, 1.0));
        memory.add(engram(ActionType.EAT,      FruitType.RED_APPLE,   -0.5, 1.0));
        memory.add(engram(ActionType.APPROACH, FruitType.GREEN_APPLE, -1.2, 1.0));

        double greenShare = shareChoosing(FruitType.GREEN_APPLE);

        assertEquals(1.2 / (1.2 + 0.65), greenShare, 0.05,
                "the better object per remembered occurrence must be preferred; a sum would have "
                        + "reversed this");
        assertTrue(greenShare > 0.5, "under a sum RED would have been the favourite");
    }

    @Test
    void a_rare_high_value_object_beats_a_frequent_low_value_one() {
        // Issue #88's acceptance criterion, at a frequency ratio that separates the two rules
        // unambiguously. This mirrors the real failure: in the campaign that found the bug,
        // APPROACH had 172,088 engrams totalling 895.9 while EAT had 1,016 totalling 11.5 — so a
        // sum chose APPROACH even though EAT was worth more than twice as much per occurrence.
        for (int i = 0; i < 100; i++) {
            memory.add(engram(ActionType.APPROACH, FruitType.RED_APPLE, -0.1, 1.0));   // +0.1 each
        }
        memory.add(engram(ActionType.APPROACH, FruitType.GREEN_APPLE, -1.0, 1.0));     // +1.0 once

        // Sum: RED 10.0 vs GREEN 1.0 → RED. Mean: RED 0.1 vs GREEN 1.0 → GREEN, by 10 : 1.
        double greenShare = shareChoosing(FruitType.GREEN_APPLE);

        assertEquals(1.0 / 1.1, greenShare, 0.05,
                "the object worth more per occurrence must be preferred regardless of how often "
                        + "the alternative was engaged");
    }

    // -----------------------------------------------------------------------
    // Neuromodulation
    // -----------------------------------------------------------------------

    @Test
    void tonic_dopamine_makes_an_unexplored_object_more_attractive() {
        // RED is known-good (+1.0); GREEN has never been encountered. Unmodulated, optimistic
        // initialisation values the unknown at the mean of the known-good, so the draw is even.
        // Tonic dopamine tilts it toward the unknown — novelty-seeking (Bunzeck & Duzel 2006).
        memory.add(engram(ActionType.APPROACH, FruitType.RED_APPLE, -1.0, 1.0));

        double unmodulated = shareChoosing(FruitType.GREEN_APPLE, 0.0);
        double modulated   = shareChoosing(FruitType.GREEN_APPLE, 2.0);

        assertEquals(0.5, unmodulated, 0.05,
                "an unknown object should start out as good as the average known one");
        assertTrue(modulated > unmodulated + 0.08,
                "tonic dopamine must raise the pull of the unexplored; got "
                        + unmodulated + " → " + modulated);
    }

    @Test
    void depleted_dopamine_does_not_push_novelty_below_the_baseline() {
        memory.add(engram(ActionType.APPROACH, FruitType.RED_APPLE, -1.0, 1.0));

        assertEquals(shareChoosing(FruitType.GREEN_APPLE, 0.0),
                shareChoosing(FruitType.GREEN_APPLE, -3.0), 0.05,
                "the modulation is one-sided, as in ActionProbabilityFilter: max(0, daTonic)");
    }

    // -----------------------------------------------------------------------
    // Diagnostics (MemoryDecisionState)
    // -----------------------------------------------------------------------

    @Test
    void no_decision_recorded_before_the_filter_ever_runs() {
        assertTrue(filter().takeLastDecision().isEmpty());
    }

    @Test
    void a_pass_through_is_recorded_as_an_undecided_consultation() {
        memory.add(engram(ActionType.APPROACH, FruitType.RED_APPLE, -1.0, 1.0));
        MemoryFilter filter = filter();

        filter.filter(actions(approach(RED_NEAR), eat(RED_NEAR)), HUNGER);   // one object only

        MemoryFilter.Decision d = filter.takeLastDecision().orElseThrow();
        assertFalse(d.decided());
        assertEquals(d.candidates(), d.returned(), "a pass-through narrows nothing");
        assertNull(d.objectType());
        assertNull(d.target());
        assertTrue(Double.isNaN(d.winningScore()));
    }

    @Test
    void a_decision_records_the_chosen_object_and_how_far_it_narrowed() {
        memory.add(engram(ActionType.APPROACH, FruitType.RED_APPLE,   -2.0, 1.0));
        memory.add(engram(ActionType.APPROACH, FruitType.GREEN_APPLE, -1.0, 1.0));
        MemoryFilter filter = filter();

        List<Action> result = filter.filter(
                actions(approach(RED_NEAR), eat(RED_NEAR), approach(GREEN_NEAR)), HUNGER);

        MemoryFilter.Decision d = filter.takeLastDecision().orElseThrow();
        assertTrue(d.decided());
        assertEquals(3, d.candidates());
        assertEquals(result.size(), d.returned());
        assertTrue(d.returned() < d.candidates());
        assertEquals(2, d.objects(), "APPROACH/EAT on RED plus APPROACH on GREEN is two objects");
        assertEquals(2, d.scored(), "both visible objects had engram evidence");
        assertEquals(result.get(0).getTarget(), d.objectType());
        assertNotNull(d.target());
        assertFalse(Double.isNaN(d.winningScore()));
        assertFalse(Double.isNaN(d.runnerUpScore()), "the object passed over was also known");
    }

    @Test
    void an_unexplored_object_on_either_side_records_nan_rather_than_a_fabricated_score() {
        memory.add(engram(ActionType.APPROACH, FruitType.RED_APPLE, -2.0, 1.0));
        MemoryFilter filter = filter();

        filter.filter(actions(approach(RED_NEAR), approach(GREEN_NEAR)), HUNGER);

        MemoryFilter.Decision d = filter.takeLastDecision().orElseThrow();
        assertEquals(2, d.objects());
        assertEquals(1, d.scored());
        boolean choseRed = d.objectType() == FruitType.RED_APPLE;
        assertTrue(choseRed ? Double.isNaN(d.runnerUpScore()) : Double.isNaN(d.winningScore()));
    }

    @Test
    void taking_a_decision_clears_it_so_a_stale_one_cannot_be_logged_twice() {
        MemoryFilter filter = filter();
        filter.filter(actions(approach(RED_NEAR), approach(GREEN_NEAR)), HUNGER);

        assertTrue(filter.takeLastDecision().isPresent());
        assertTrue(filter.takeLastDecision().isEmpty(),
                "a cycle where the filter was never reached must not re-log the previous decision");
    }

    @Test
    void filter_type_is_memory() {
        assertEquals(ActionSelectionType.MEMORY, filter().getFilterType());
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private MemoryFilter filter() {
        return new MemoryFilter(memory, new Random(SEED));
    }

    /** Fraction of {@link #DRAWS} draws that narrowed to {@code target}, between RED and GREEN. */
    private double shareChoosing(WorldObjectType target) {
        return shareChoosing(target, null);
    }

    private double shareChoosing(WorldObjectType target, Double daTonic) {
        MemoryFilter filter = filter();
        if (daTonic != null) filter.setModulation(daTonic);

        int hits = 0;
        for (int i = 0; i < DRAWS; i++) {
            List<Action> result = filter.filter(
                    actions(approach(RED_NEAR), approach(GREEN_NEAR)), HUNGER);
            assertEquals(1, result.size(), "each object offers one candidate action in this fixture");
            if (result.get(0).getTarget() == target) hits++;
        }
        return hits / (double) DRAWS;
    }

    /** ActionSelection hands filters a mutable list; mirror that rather than testing on List.of(). */
    private static List<Action> actions(Action... acts) {
        return new ArrayList<>(List.of(acts));
    }

    private static Action approach(Perception perception) {
        return new Action(ActionType.APPROACH, perception);
    }

    private static Action eat(Perception perception) {
        return new Action(ActionType.EAT, perception);
    }

    private static Emotion emotion(String name, double level) {
        Emotion e = new Emotion(name);
        e.setLevel(level);
        return e;
    }

    private static Engram engram(ActionType action, FruitType objectType, double emotionDelta, double eligibility) {
        Perception p = new Perception(objectType, new SequentialId(99), 10, 0);
        Emotion e = emotion("hunger", 3.0);
        return new Engram(action, new SequentialId(99), e, p, 1L, emotionDelta, eligibility, 1L);
    }

    // Simple in-memory stub — no Akka, no DB.
    private static class ListMemorySystem implements MemorySystem {
        private final List<Engram> engrams = new ArrayList<>();

        void add(Engram e) { engrams.add(e); }

        @Override public List<Engram> getRecentEngrams(int windowSize) {
            int skip = Math.max(0, engrams.size() - windowSize);
            return engrams.subList(skip, engrams.size());
        }

        @Override public void addShortTermMemory(ShortTermMemory stm) {}
        @Override public List<ShortTermMemory> getMemories(SequentialId id) { return List.of(); }
        @Override public void tickDecisionCycle() {}
        @Override public long currentDecisionCycle() { return 0; }
        @Override public List<Engram> reinforceWarmTraces(double emotionDelta, long currentCycle) { return List.of(); }
        @Override public void addEngram(Engram engram) { engrams.add(engram); }
    }
}
