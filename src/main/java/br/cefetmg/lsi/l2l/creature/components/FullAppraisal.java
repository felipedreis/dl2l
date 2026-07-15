package br.cefetmg.lsi.l2l.creature.components;

import br.cefetmg.lsi.l2l.cluster.settings.LearningSettings;
import br.cefetmg.lsi.l2l.common.Constants;
import br.cefetmg.lsi.l2l.common.SequentialId;
import br.cefetmg.lsi.l2l.creature.actionSelector.ActionFilter;
import br.cefetmg.lsi.l2l.creature.actionSelector.ActionProbabilityFilter;
import br.cefetmg.lsi.l2l.creature.actionSelector.ActionSelection;
import br.cefetmg.lsi.l2l.creature.actionSelector.ActionTendencyFilter;
import br.cefetmg.lsi.l2l.creature.actionSelector.MemoryFilter;
import br.cefetmg.lsi.l2l.creature.actionSelector.RandomFilter;
import br.cefetmg.lsi.l2l.creature.actionSelector.TargetDistanceFilter;
import br.cefetmg.lsi.l2l.creature.actionSelector.WorldModelFilter;
import br.cefetmg.lsi.l2l.creature.ml.MLServiceExtension;
import br.cefetmg.lsi.l2l.creature.ml.ModelContract;
import br.cefetmg.lsi.l2l.creature.ml.WorldModelEngine;
import br.cefetmg.lsi.l2l.creature.bd.ActionSelectionType;
import br.cefetmg.lsi.l2l.creature.bd.ChangeStimulusState;
import br.cefetmg.lsi.l2l.creature.bd.ChangeStimulusStateBuilder;
import br.cefetmg.lsi.l2l.creature.bd.ChosenActionState;
import br.cefetmg.lsi.l2l.creature.common.Action;
import br.cefetmg.lsi.l2l.creature.common.ActionType;
import br.cefetmg.lsi.l2l.creature.common.Perception;
import br.cefetmg.lsi.l2l.creature.memory.MemorySystem;
import br.cefetmg.lsi.l2l.creature.memory.ShortTermMemory;
import br.cefetmg.lsi.l2l.creature.bd.SleepEpisodeState;
import br.cefetmg.lsi.l2l.creature.ml.SleepStarted;
import br.cefetmg.lsi.l2l.stimuli.CholinergicStimulus;
import br.cefetmg.lsi.l2l.stimuli.CorticalStimulus;
import br.cefetmg.lsi.l2l.stimuli.EmotionalStimulus;
import br.cefetmg.lsi.l2l.stimuli.EndocrineState;
import br.cefetmg.lsi.l2l.stimuli.NeuromodulatorState;
import br.cefetmg.lsi.l2l.stimuli.Stimulus;
import br.cefetmg.lsi.l2l.stimuli.TediumStimulus;
import br.cefetmg.lsi.l2l.world.PlantType;
import br.cefetmg.lsi.l2l.world.Self;
import br.cefetmg.lsi.l2l.world.WorldObjectType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Created by felipe on 02/01/17.
 */
public class FullAppraisal extends CreatureComponent {

    private final LearningSettings learningSettings;
    private final MLServiceExtension.Impl mlExt;
    // Populated after preStart() creates the filter; shared with JepaExpectancyPredictor.
    private final AtomicReference<WorldModelFilter> wmFilterRef;

    private ActionSelection actionSelection;
    private MemorySystem memorySystem;
    private WorldModelEngine worldModelEngine;
    private WorldModelFilter worldModelFilter;
    private ModelContract contract;

    // Neuromodulation: eventually-consistent tonic snapshots broadcast by NeuromodulatorSystem.
    private ActionProbabilityFilter affordanceFilter;
    private double daTonic = 0.0;
    private double serotoninTonic = 0.0;
    private double orexinTonic = 0.0;
    // Endocrine: cortisol tonic level from HPA axis, cached for encodeInternalState().
    private double cortisolTonic = 0.0;

    private long cognitiveCycle = 0;
    private final SleepEpisode sleepEpisode = new SleepEpisode();

    public FullAppraisal(SequentialId id, LearningSettings learningSettings, MLServiceExtension.Impl mlExt) {
        this(id, learningSettings, mlExt, null);
    }

    public FullAppraisal(SequentialId id, LearningSettings learningSettings, MLServiceExtension.Impl mlExt,
                         AtomicReference<WorldModelFilter> wmFilterRef) {
        super(id);
        this.learningSettings = learningSettings;
        this.mlExt = mlExt;
        this.wmFilterRef = wmFilterRef;
    }

    @Override
    public void preStart() throws Exception {
        super.preStart();
        memorySystem = creature.memory();

        // When mlExt is null (e.g. TestingCreature), the WORLD_MODEL filter is silently
        // disabled regardless of LearningSettings. Other filters still respect settings.
        boolean worldModelAvailable = mlExt != null && learningSettings.isFilterEnabled(ActionSelectionType.WORLD_MODEL);
        if (worldModelAvailable) {
            contract = ModelContract.load(mlExt.modelDir());
            worldModelEngine = new WorldModelEngine(mlExt, id.key);
        }

        List<ActionFilter> filterList = new ArrayList<>();
        // Innate emotion→action coupling runs first as a coarse prior (soft, pass-through when empty),
        // so e.g. a hungry creature pursues EAT/APPROACH instead of SLEEP before the learned filters.
        if (learningSettings.isActionTendencyEnabled()) {
            filterList.add(new ActionTendencyFilter(learningSettings.getActionTendencies()));
        }
        for (ActionSelectionType type : LearningSettings.MASTER_FILTER_ORDER) {
            if (!learningSettings.isFilterEnabled(type)) continue;
            switch (type) {
                case TARGET_DISTANCE -> filterList.add(new TargetDistanceFilter());
                case AFFORDANCE      -> {
                    affordanceFilter = new ActionProbabilityFilter(creature.operantConditioning());
                    filterList.add(affordanceFilter);
                }
                case MEMORY          -> filterList.add(new MemoryFilter(memorySystem));
                case WORLD_MODEL     -> {
                    if (worldModelAvailable) {
                        worldModelFilter = new WorldModelFilter(worldModelEngine, contract);
                        filterList.add(worldModelFilter);
                        if (wmFilterRef != null) wmFilterRef.set(worldModelFilter);
                    }
                }
                case RANDOM          -> filterList.add(new RandomFilter());
                default              -> logger.warning("FullAppraisal: unknown filter type " + type + ", skipping");
            }
        }
        actionSelection = new ActionSelection(filterList);
    }

    @Override
    public void postStop() throws Exception {
        super.postStop();
        if (worldModelEngine != null) worldModelEngine.close();
    }

    @Override
    public void onReceive(Object message) {
        @SuppressWarnings("unchecked")
        List<Stimulus> stimuli = (List<Stimulus>) message;
        for (Stimulus stimulus : stimuli) {
            switch (stimulus) {
                case NeuromodulatorState nm -> onNeuromodulatorState(nm);
                case EndocrineState es      -> onEndocrineState(es);
                case EmotionalStimulus em   -> onEmotionalStimulus(em);
                default                     -> {}
            }
        }
    }

    /**
     * Caches the latest tonic neuromodulator snapshot from {@link NeuromodulatorSystem}.
     *
     * <p>Dopamine modulates the affordance filter's exploration–exploitation balance:
     * high DA increases willingness to sample low-probability actions (exploration).
     * Serotonin biases the same filter toward patience (slows consummatory urgency).
     * Orexin is used in {@link #definePossibleActions} to gate SLEEP out of the action
     * set when the creature is too alert to sleep. All three are eventually-consistent —
     * the snapshot may lag one cognitive cycle behind the true tonic level.
     */
    private void onNeuromodulatorState(NeuromodulatorState nm) {
        daTonic        = nm.dopamineTonic;
        serotoninTonic = nm.serotoninTonic;
        orexinTonic    = nm.orexinTonic;
    }

    /**
     * Receives the HPA-axis broadcast after each cortisol tick.
     *
     * <p>{@link EndocrineSystem} regulates STRESS directly into {@link EmotionalSystem} on
     * every tick, so FullAppraisal does not need to re-apply the stress level — it arrives
     * already encoded in the next {@link EmotionalStimulus} via {@code getMaxArousal()}.
     * The cortisol tonic level is cached here for {@link #encodeInternalState()} so the
     * JEPA internal encoder can condition on HPA-axis state.
     */
    private void onEndocrineState(EndocrineState es) {
        cortisolTonic = es.cortisolTonic;
    }

    /**
     * Core cognitive appraisal cycle: selects an action, manages sleep state, and dispatches
     * the motor command to {@link EffectorCortex}.
     *
     * <p>Ordering is intentional: action selection must see the current neuromodulator tonics
     * (updated by {@link #onNeuromodulatorState} in the same batch before this stimulus),
     * the hysteresis gate enforces minimum sleep duration before action can change, and
     * sleep state updates must happen on the <em>final</em> action after the gate.
     */
    private void onEmotionalStimulus(EmotionalStimulus emotional) {
        cognitiveCycle++;
        memorySystem.tickDecisionCycle();

        Action action = selectAction(emotional);
        updateMemory(action, emotional);
        dispatchTediumStimulus(action.type);

        action = enforceHysteresisGate(action, emotional.behaviouralEfficiency);
        CorticalStimulus cortical = updateSleepState(action, emotional.behaviouralEfficiency);

        creature.effectorCortex().tell(cortical);
        persistDecision(emotional, cortical, action);
    }

    /**
     * Runs the full action-selection pipeline for the current cognitive cycle.
     *
     * <p>Before selection the JEPA world model (if loaded) is supplied with the creature's
     * current homeostatic state so the internal encoder can condition predictions on it.
     * The affordance filter is re-modulated by the latest dopamine and serotonin tonics so
     * neuromodulation influences the exploration-exploitation balance at selection time.
     * The orexin gate in {@link #definePossibleActions} removes SLEEP from the candidate
     * set when the creature is too alert, ensuring sleep is only possible under genuine
     * sleep pressure.
     */
    private Action selectAction(EmotionalStimulus emotional) {
        if (worldModelFilter != null) {
            worldModelFilter.updateInternalState(encodeInternalState());
        }
        if (learningSettings.isNeuromodulationEnabled() && affordanceFilter != null) {
            affordanceFilter.setModulation(daTonic, serotoninTonic);
        }
        List<Action> possibleActions = definePossibleActions(emotional.getPerceptions());
        return actionSelection.selectOne(possibleActions, emotional.getMaxEmotion());
    }

    /**
     * Records the selected action and its perceptual context in short-term memory.
     *
     * <p>Short-term memory (STM) is the raw episodic buffer that feeds the memory
     * consolidation pipeline during sleep. Each entry stores the action taken, the
     * target perception, the dominant emotion at decision time, and the cognitive cycle
     * counter so episodes can be ordered and replayed by {@link MemoryConsolidator}.
     */
    private void updateMemory(Action action, EmotionalStimulus emotional) {
        ShortTermMemory stm = new ShortTermMemory(
                action.type, action.perception.id, emotional.maxEmotion,
                action.perception, cognitiveCycle);
        memorySystem.addShortTermMemory(stm);

        logger.fine(String.format("FullAppraisal[%s]: selected=%s for=%s angle=%.3f dist=%.1f",
                id, action.type, action.perception.objectType,
                action.perception.angle, action.perception.distance));
    }

    /**
     * Applies the anti-micro-nap hysteresis gate and returns the (possibly overridden) action.
     *
     * <p>Once the creature enters sleep, it must remain asleep for at least
     * {@code MIN_SLEEP_TICKS} cognitive cycles before any wake transition is allowed.
     * Without this gate, the orexin boundary (sleep pressure ≈ 3.5 = gate threshold) is
     * numerically unstable and the creature flickers in and out of sleep every 1–2 cycles,
     * accumulating negligible cholinergic clearing and never actually resting.
     * When the gate overrides the selected action, cortical parameters are recomputed so
     * the motor command correctly reflects the enforced SLEEP posture (speed=0, focus=0).
     */
    private Action enforceHysteresisGate(Action action, double behaviouralEfficiency) {
        if (sleepEpisode.isActive()
                && action.type != ActionType.SLEEP
                && sleepEpisode.dwellTicks < Constants.MIN_SLEEP_TICKS) {
            return new Action(ActionType.SLEEP, action.perception);
        }
        return action;
    }

    /**
     * Updates sleep episode tracking, sends homeostatic clearing signals, and produces
     * the {@link CorticalStimulus} motor command for the final action.
     *
     * <p>On each SLEEP tick a cholinergic clearing signal is batched; the batch is flushed
     * to {@link HomeostaticRegulation} every {@code HOMEO_BATCH_SIZE} ticks (matching
     * PartialAppraisal's metabolic batching rate) to prevent queue starvation. A partial
     * batch is always flushed on wake so no accumulated clearing is lost.
     * On sleep onset a {@link SleepStarted} signal is sent to {@link MemoryConsolidator}
     * to trigger asynchronous adapter training on the background ML thread.
     */
    private CorticalStimulus updateSleepState(Action action, double behaviouralEfficiency) {
        if (action.type == ActionType.SLEEP) {
            sleepEpisode.batchTickCount++;
            if (sleepEpisode.batchTickCount >= Constants.HOMEO_BATCH_SIZE) {
                creature.homeostatic().tell(new CholinergicStimulus(id, nextStimulusId(),
                        sleepEpisode.batchTickCount * Constants.CHOLINERGIC_DELTA));
                sleepEpisode.batchTickCount = 0;
            }
            if (sleepEpisode.onSleepTick(cognitiveCycle)) {
                logger.fine(String.format("FullAppraisal[%s]: SLEEP onset at cycle %d", id, cognitiveCycle));
                creature.memoryConsolidator().tell(new SleepStarted(cognitiveCycle));
            }
        } else if (sleepEpisode.isActive()) {
            if (sleepEpisode.batchTickCount > 0) {
                creature.homeostatic().tell(new CholinergicStimulus(id, nextStimulusId(),
                        sleepEpisode.batchTickCount * Constants.CHOLINERGIC_DELTA));
            }
            logger.fine(String.format("FullAppraisal[%s]: WAKE after %d sleep cycles at cycle %d",
                    id, sleepEpisode.dwellTicks, cognitiveCycle));
            persist(new SleepEpisodeState(id.key, sleepEpisode.onsetCycle, cognitiveCycle, sleepEpisode.dwellTicks));
            sleepEpisode.onWake();
        }

        CorticalStimulus cortical = produceCortical(action, behaviouralEfficiency);
        logger.fine(String.format("FullAppraisal[%s]: cortical angle=%.3f speed=%.3f",
                id, cortical.angle, cortical.speed));
        return cortical;
    }

    /**
     * Persists the decision audit trail: the stimulus-response change record and the
     * chosen-action log entry (includes which filter type made the final selection and,
     * when a world-model filter is active, how long inference took).
     */
    private void persistDecision(EmotionalStimulus emotional, CorticalStimulus cortical, Action action) {
        long inferenceMs = (worldModelFilter != null)
                ? worldModelFilter.getLastInferenceDurationMs() : 0L;
        ChangeStimulusState change = new ChangeStimulusStateBuilder(this, id)
                .buildOneReceivedOneEmitted(emotional, cortical);
        ChosenActionState chosenActionState = new ChosenActionState(change,
                actionSelection.getLastUsedFilterType(), action.type, action.perception.id,
                inferenceMs);
        persist(change, chosenActionState);
    }

    private static final class SleepEpisode {
        boolean active = false;
        int dwellTicks = 0;
        long onsetCycle = 0L;
        int batchTickCount = 0;

        boolean isActive() { return active; }

        /** Returns true on the first tick (onset); increments dwellTicks on subsequent ticks. */
        boolean onSleepTick(long currentCycle) {
            if (!active) {
                active = true;
                dwellTicks = 0;
                onsetCycle = currentCycle;
                return true;
            }
            dwellTicks++;
            return false;
        }

        void onWake() {
            active = false;
            dwellTicks = 0;
            batchTickCount = 0;
        }
    }

    /**
     * Converts the selected action into a motor command ({@link CorticalStimulus}).
     *
     * <p>Speed and focus defaults are scaled by behavioural efficiency (Yerkes-Dodson),
     * clamped to {@code [MIN_STEP, MAX_STEP]} and {@code [MIN_VISION_FIELD_OPENING,
     * MAX_VISION_FIELD_OPENING]}. Each action type then overrides the defaults as needed:
     * APPROACH narrows focus linearly with proximity (attentional narrowing feedback loop);
     * SLEEP sets speed=0 and focus=0 (eye closed); EAT locks on the target at contact.
     */
    private CorticalStimulus produceCortical(Action action, double behaviouralEfficiency) {
        Perception perception = action.perception;
        double defaultFocus = Math.max(Constants.MAX_VISION_FIELD_OPENING * behaviouralEfficiency, Constants.MIN_VISION_FIELD_OPENING);
        double defaultSpeed = Math.max(Constants.MAX_STEP * behaviouralEfficiency, Constants.MIN_STEP);

        return switch (action.type) {
            case AVOID ->
                // 45° offset from target direction (pass by, not head-on)
                cortical(action, perception.angle + Math.PI / 4, defaultFocus, defaultSpeed);

            case ESCAPE ->
                // run directly away from threat
                cortical(action, perception.angle + Math.PI, defaultFocus, defaultSpeed);

            case APPROACH -> {
                // Narrow focus as creature nears target; wide field at max range, locked at contact.
                double focus = Constants.MIN_VISION_FIELD_OPENING
                        + (Constants.MAX_VISION_FIELD_OPENING - Constants.MIN_VISION_FIELD_OPENING)
                        * Math.min(perception.distance / Constants.DEFAULT_VISION_FIELD_RADIUS, 1.0);
                yield cortical(action, perception.angle, focus, defaultSpeed);
            }

            case EAT ->
                cortical(action, perception.angle, Constants.MIN_VISION_FIELD_OPENING, 0);

            case WANDER ->
                // symmetric random turn ±MAX_ROTATE_ANGLE degrees around current heading
                cortical(action,
                        perception.angle + (2 * new Random().nextDouble() - 1) * Math.toRadians(Constants.MAX_ROTATE_ANGLE),
                        defaultFocus, defaultSpeed);

            case OBSERVE ->
                cortical(action, perception.angle, Constants.MAX_VISION_FIELD_OPENING, 0);

            case SLEEP ->
                // focus=0.0: eye closed; gate enforced in Eye.onReceive (< MIN check)
                cortical(action, 0, 0.0, 0);

            // TURN, TOUCH, PLAY are not yet wired into action selection; fall back to defaults.
            default -> cortical(action, perception.angle, defaultFocus, defaultSpeed);
        };
    }

    private CorticalStimulus cortical(Action action, double angle, double focus, double speed) {
        return new CorticalStimulus(this.id, nextStimulusId(), action.type, action.perception.id, angle, focus, speed);
    }

    /**
     * Emits a {@link TediumStimulus} to regulate the tedium affect based on the selected action.
     *
     * <p>This pathway is only active when the neuromodulator loop is <em>disabled</em>.
     * When neuromodulation is on, tedium is regulated by dopamine absence in
     * {@link NeuromodulatorSystem} — re-applying it here would double-count the effect.
     * WANDER relieves tedium (novelty); OBSERVE increases it (passive fixation); all
     * other non-sleep actions apply a mild idle rate.
     */
    private void dispatchTediumStimulus(ActionType selectedAction) {
        if (learningSettings.isNeuromodulatorLoopActive()) return;
        if (selectedAction == ActionType.SLEEP) return;

        double delta = switch (selectedAction) {
            case WANDER  -> -Constants.TEDIUM_WANDER_RELIEF;
            case OBSERVE ->  Constants.TEDIUM_OBSERVE_RATE;
            default      ->  Constants.TEDIUM_IDLE_RATE;
        };

        creature.homeostatic().tell(new TediumStimulus(id, nextStimulusId(), delta, selectedAction));
    }

    /**
     * Encodes the creature's current internal state into a float vector for the JEPA
     * dual-encoder's internal state branch.
     *
     * <p>Feature order is defined by {@code model_contract.json}'s
     * {@code internalStateFeatureOrder} field. Three prefix namespaces are supported:
     * <ul>
     *   <li>{@code ht_*}  — homeostatic drive level from {@link EmotionalSystem}</li>
     *   <li>{@code nm_*}  — neuromodulator tonic (dopamine, serotonin, orexin)</li>
     *   <li>{@code end_*} — endocrine value (cortisol_tonic from HPA axis)</li>
     * </ul>
     * Returns {@code null} when no dual encoder is loaded.
     */
    private float[] encodeInternalState() {
        if (worldModelFilter == null || !contract.hasDualEncoder) return null;
        List<String> featureOrder = contract.internalStateFeatureOrder;
        float[] state = new float[featureOrder.size()];
        for (int i = 0; i < featureOrder.size(); i++) {
            String feature = featureOrder.get(i);
            double raw;
            if (feature.startsWith("ht_")) {
                raw = creature.emotions().getLevel(feature.substring(3));
            } else if (feature.startsWith("nm_")) {
                raw = switch (feature.substring(3)) {
                    case "dopamine"  -> daTonic;
                    case "serotonin" -> serotoninTonic;
                    case "orexin"    -> orexinTonic;
                    default          -> 0.0;
                };
            } else if (feature.startsWith("end_")) {
                raw = switch (feature.substring(4)) {
                    case "cortisol_tonic" -> cortisolTonic;
                    default               -> 0.0;
                };
            } else {
                raw = 0.0;
            }
            // Apply per-feature z-score normalisation if stats are present in the contract.
            if (contract.hMeans != null && contract.hStds != null
                    && i < contract.hMeans.size() && i < contract.hStds.size()) {
                double std = contract.hStds.get(i);
                raw = (raw - contract.hMeans.get(i)) / (std < 1e-6 ? 1e-6 : std);
            }
            state[i] = (float) raw;
        }
        return state;
    }

    private List<Action> definePossibleActions(List<Perception> perceptions) {
        List<Action> actions = new ArrayList<>();
        for (Perception perception : perceptions) {
            actions.addAll(actionsForPerception(perception));
        }
        if (learningSettings.isOrexinEnabled() && orexinTonic >= Constants.OREXIN_SLEEP_GATE_THRESHOLD) {
            actions.removeIf(a -> a.type == ActionType.SLEEP);
        }
        return actions;
    }

    private List<Action> actionsForPerception(Perception perception) {
        if (!perception.objectType.isDefined()) {
            return Arrays.asList(
                new Action(ActionType.SLEEP, perception),
                new Action(ActionType.WANDER, perception),
                new Action(ActionType.OBSERVE, perception)
            );
        }
        WorldObjectType type = perception.objectType.get();
        if (type instanceof Self) {
            return Arrays.asList(
                new Action(ActionType.SLEEP, perception),
                new Action(ActionType.WANDER, perception)
            );
        }
        if (perception.distance > 0) {
            return actionsAtDistance(perception);
        }
        return actionsAtContact(perception, type);
    }

    private List<Action> actionsAtDistance(Perception perception) {
        // WANDER is always available so an exploratory (bored-but-content) creature can leave a
        // perceived object and explore, rather than only staring at it (OBSERVE). Kept last so the
        // priority-first filters still see APPROACH first.
        return Arrays.asList(
            new Action(ActionType.APPROACH, perception),
            new Action(ActionType.AVOID, perception),
            new Action(ActionType.SLEEP, perception),
            new Action(ActionType.OBSERVE, perception),
            new Action(ActionType.WANDER, perception)
        );
    }

    private List<Action> actionsAtContact(Perception perception, WorldObjectType type) {
        // Painful plants (no heal) trigger escape instead of rest.
        ActionType restOrEscape = (type instanceof PlantType && ((PlantType) type).healAmount == 0)
                ? ActionType.ESCAPE : ActionType.SLEEP;
        return Arrays.asList(
            new Action(ActionType.EAT, perception),
            new Action(ActionType.AVOID, perception),
            new Action(restOrEscape, perception),
            new Action(ActionType.WANDER, perception)
        );
    }
}
