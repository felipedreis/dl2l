package br.cefetmg.lsi.l2l.creature.components;

import br.cefetmg.lsi.l2l.cluster.settings.LearningSettings;
import br.cefetmg.lsi.l2l.common.SequentialId;
import br.cefetmg.lsi.l2l.creature.bd.ActionProbabilityState;
import br.cefetmg.lsi.l2l.creature.bd.ChangeStimulusState;
import br.cefetmg.lsi.l2l.creature.bd.ChangeStimulusStateBuilder;
import br.cefetmg.lsi.l2l.creature.bd.EngramState;
import br.cefetmg.lsi.l2l.creature.bd.ExpectancyState;
import br.cefetmg.lsi.l2l.creature.bd.PersistenceState;
import br.cefetmg.lsi.l2l.creature.common.ActionType;
import br.cefetmg.lsi.l2l.creature.conditioning.ActionProbability;
import br.cefetmg.lsi.l2l.creature.conditioning.OperantConditioning;
import br.cefetmg.lsi.l2l.creature.conditioning.expectancy.ExpectancyContext;
import br.cefetmg.lsi.l2l.creature.conditioning.expectancy.ExpectancyPredictor;
import br.cefetmg.lsi.l2l.creature.memory.Engram;
import br.cefetmg.lsi.l2l.creature.memory.MemorySystem;
import br.cefetmg.lsi.l2l.creature.memory.ShortTermMemory;
import br.cefetmg.lsi.l2l.stimuli.DopaminergicStimulus;
import br.cefetmg.lsi.l2l.stimuli.EvaluationStimulus;
import br.cefetmg.lsi.l2l.stimuli.Stimulus;
import br.cefetmg.lsi.l2l.world.WorldObjectType;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Created by felipe on 02/01/17.
 *
 * <p>Reward valuation and learning. When the expectancy loop is enabled (issue #57), Valuation
 * plays the VTA/SNc + striatum role: it computes a reward-prediction error against the
 * {@link ExpectancyPredictor} baseline, emits a phasic {@link DopaminergicStimulus}, and uses the
 * signed/graded RPE to drive both operant conditioning and memory-trace consolidation. When the
 * loop is disabled it keeps the legacy binary-valence behaviour byte-for-byte.
 */
public class Valuation extends CreatureComponent {

    private final LearningSettings learningSettings;

    private OperantConditioning operantConditioning;

    private ExpectancyPredictor expectancy;

    private MemorySystem memorySystem;

    /** Orders {@link ActionProbabilityState} events within a cycle, which can hold several. */
    private long conditioningSeq = 0;

    public Valuation(SequentialId id, LearningSettings learningSettings) {
        super(id);
        this.learningSettings = learningSettings;
    }

    @Override
    public void preStart() throws Exception {
        super.preStart();
        operantConditioning = creature.operantConditioning();
        expectancy = creature.expectancy();
        memorySystem = creature.memory();
    }

    @Override
    public void onReceive(Object message) {
        List<Stimulus> stimuli = (List) message;

        for (Stimulus aStimuli : stimuli) {

            if (aStimuli instanceof EvaluationStimulus) {

                EvaluationStimulus evaluation = (EvaluationStimulus) aStimuli;

                if (learningSettings.isExpectancyEnabled()) {
                    evaluateWithExpectancy(evaluation);
                } else {
                    evaluateLegacy(evaluation);
                }

                ChangeStimulusState change = new ChangeStimulusStateBuilder(this, this.id)
                        .buildOneReceivedOneEmitted(evaluation, null);

                persist(change);
            }

        }
    }

    /** Legacy path: emotion-blind binary valence, magnitude discarded (pre-issue-57 behaviour). */
    private void evaluateLegacy(EvaluationStimulus evaluation) {
        boolean valence = evaluation.arousalVariation < 0;

        logger.fine(String.format("Valuation[%s]: action=%s type=%s arousalVariation=%.3f valence=%s",
                id, evaluation.executedAction, evaluation.type,
                evaluation.arousalVariation, valence ? "positive" : "negative"));

        operantConditioning.varyProbability(evaluation.type, evaluation.executedAction, 1, valence);
        persistConditioning(evaluation.type, evaluation.executedAction, valence ? 1 : -1);

        List<Engram> produced = memorySystem.reinforceWarmTraces(
                evaluation.arousalVariation, memorySystem.currentDecisionCycle());
        persistEngrams(produced);
    }

    /**
     * Expectancy path: RPE = reward - expected, reward = -arousalVariation. Positive RPE means the
     * outcome beat expectation. Note {@code -rpe} is fed to the memory system so that its
     * emotionDelta sign convention (negative = good) is preserved and reduces to the legacy value
     * when {@code expected == 0}.
     */
    private void evaluateWithExpectancy(EvaluationStimulus evaluation) {
        ExpectancyContext ctx = evaluation.expectancyContext;
        double reward = -evaluation.arousalVariation;
        double expected = expectancy.expected(ctx, evaluation.type, evaluation.executedAction);
        double rpe = reward - expected;

        expectancy.observe(ctx, evaluation.type, evaluation.executedAction, reward);

        // Phasic dopamine release carrying the prediction error (the molecule is the message).
        creature.neuromodulators().tell(new DopaminergicStimulus(
                id, nextStimulusId(), rpe, evaluation.type, evaluation.executedAction));

        logger.fine(String.format(
                "Valuation[%s]: action=%s type=%s drive=%s level=%.3f reward=%.3f expected=%.3f rpe=%.3f",
                id, evaluation.executedAction, evaluation.type, ctx.dominantDriveName(),
                ctx.dominantDriveLevel(), reward, expected, rpe));

        // Graded reinforcement: magnitude |rpe|, direction from the sign of the surprise.
        operantConditioning.varyProbability(
                evaluation.type, evaluation.executedAction, Math.abs(rpe), rpe > 0);
        persistConditioning(evaluation.type, evaluation.executedAction,
                rpe > 0 ? Math.abs(rpe) : -Math.abs(rpe));

        List<Engram> produced = memorySystem.reinforceWarmTraces(
                -rpe, memorySystem.currentDecisionCycle());
        persistEngrams(produced);

        persist(new ExpectancyState(id.key, memorySystem.currentDecisionCycle(),
                learningSettings.getExpectancyMode().name(), ctx.dominantDriveName(),
                ctx.dominantDriveLevel(), evaluation.type.name(), evaluation.executedAction,
                expected, reward, rpe));
    }

    /**
     * Snapshots the affected target's whole action-probability table right after it changed —
     * one {@link ActionProbabilityState} per action, all in a single {@code persist(...)} call so
     * the six rows always land in one batch. Called from both evaluation paths: the legacy one
     * writes no {@link ExpectancyState}, so this is the only record of conditioning the
     * legacy-minimal experiment arms produce.
     *
     * <p>Only the evaluated target is written. The other targets' tables are untouched by this
     * event, so re-emitting them would be pure duplication — readers forward-fill instead.
     */
    private void persistConditioning(WorldObjectType target, ActionType reinforced, double delta) {
        List<ActionProbability> probabilities = operantConditioning.getProbabilities(target).orElse(null);
        if (probabilities == null || probabilities.isEmpty()) return;

        long seq = conditioningSeq++;
        long cycle = memorySystem.currentDecisionCycle();
        long now = System.currentTimeMillis();

        PersistenceState[] rows = new PersistenceState[probabilities.size()];
        for (int i = 0; i < probabilities.size(); i++) {
            ActionProbability ap = probabilities.get(i);
            rows[i] = new ActionProbabilityState(id.key, seq, now, cycle, target.name(),
                    ap.getAction(), ap.getProbability(), reinforced, delta);
        }
        persist(rows);
    }

    private void persistEngrams(List<Engram> produced) {
        for (Engram e : produced) {
            persist(new EngramState(id.key, e.actionType(), e.layCycle(), e.reinforcedCycle(),
                    e.reinforcedCycle() - e.layCycle(), e.eligibility(), e.emotionDelta(),
                    e.perception() != null ? e.perception().objectType.getOrElse(null) : null,
                    e.emotionAtDecision() != null ? e.emotionAtDecision().getName() : null,
                    e.emotionAtDecision() != null ? e.emotionAtDecision().getLevel() : Double.NaN));
        }
    }
}
