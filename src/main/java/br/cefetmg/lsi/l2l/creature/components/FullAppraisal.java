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
import java.util.List;
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
import br.cefetmg.lsi.l2l.stimuli.CorticalStimulus;
import br.cefetmg.lsi.l2l.stimuli.EmotionalStimulus;
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

/**
 * Created by felipe on 02/01/17.
 */
public class FullAppraisal extends CreatureComponent {

    private final LearningSettings learningSettings;
    private final MLServiceExtension.Impl mlExt;

    private ActionSelection actionSelection;

    private MemorySystem memorySystem;

    private WorldModelEngine worldModelEngine;
    private WorldModelFilter worldModelFilter;  // kept for updateInternalState calls; null when no dual encoder
    private ModelContract contract;

    // Neuromodulation: cached tonic levels (eventually-consistent) and the filter they modulate.
    private ActionProbabilityFilter affordanceFilter;
    private double daTonic = 0.0;
    private double serotoninTonic = 0.0;

    private long cognitiveCycle = 0;
    private boolean inSleep = false;
    private int sleepDwellTicks = 0;
    private long sleepOnsetCycle = 0;

    public FullAppraisal(SequentialId id, LearningSettings learningSettings, MLServiceExtension.Impl mlExt) {
        super(id);
        this.learningSettings = learningSettings;
        this.mlExt = mlExt;
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
        List stimuli = (List) message;

        for (Object aStimuli : stimuli) {
            Stimulus stimulus = (Stimulus) aStimuli;

            if (stimulus instanceof NeuromodulatorState) {
                // Cache the slow-varying tonic levels for the next action selection.
                NeuromodulatorState nm = (NeuromodulatorState) stimulus;
                daTonic = nm.dopamineTonic;
                serotoninTonic = nm.serotoninTonic;
                continue;
            }

            if (stimulus instanceof EmotionalStimulus) {
                cognitiveCycle++;
                memorySystem.tickDecisionCycle();
                EmotionalStimulus emotional = (EmotionalStimulus) stimulus;

                // Dual-encoder: supply current homeostatic state to WorldModelFilter
                // before action selection so inference can condition on it.
                if (worldModelFilter != null) {
                    worldModelFilter.updateInternalState(encodeInternalState());
                }

                // Tonic neuromodulators bias the affordance sampler (exploration / rest) at
                // selection time; a no-op when neuromodulation is disabled.
                if (learningSettings.isNeuromodulationEnabled() && affordanceFilter != null) {
                    affordanceFilter.setModulation(daTonic, serotoninTonic);
                }

                List<Action> possibleActions = definePossibleActions(emotional.getPerceptions());
                Action action = actionSelection.selectOne(possibleActions, emotional.getMaxEmotion());

                ShortTermMemory stm = new ShortTermMemory(
                        action.type, action.perception.id, emotional.maxEmotion,
                        action.perception, cognitiveCycle);
                memorySystem.addShortTermMemory(stm);

                logger.info(String.format("FullAppraisal[%s]: selected=%s for=%s angle=%.3f dist=%.1f",
                        id, action.type, action.perception.objectType,
                        action.perception.angle, action.perception.distance));

                dispatchTediumStimulus(action.type);

                CorticalStimulus cortical = produceCortical(action, emotional.behaviouralEfficiency);

                logger.info(String.format("FullAppraisal[%s]: cortical angle=%.3f speed=%.3f",
                        id, cortical.angle, cortical.speed));

                // Anti-micro-nap hysteresis: once asleep, hold SLEEP for at least
                // MIN_SLEEP_TICKS cognitive cycles before allowing any wake transition.
                if (inSleep && action.type != ActionType.SLEEP
                        && sleepDwellTicks < Constants.MIN_SLEEP_TICKS) {
                    action = new Action(ActionType.SLEEP, action.perception);
                    cortical = produceCortical(action, emotional.behaviouralEfficiency);
                }

                // Update sleep state and fire consolidation signals.
                if (action.type == ActionType.SLEEP) {
                    if (!inSleep) {
                        inSleep = true;
                        sleepDwellTicks = 0;
                        sleepOnsetCycle = cognitiveCycle;
                        logger.info(String.format("FullAppraisal[%s]: SLEEP onset at cycle %d", id, cognitiveCycle));
                        creature.memoryConsolidator().tell(new SleepStarted(cognitiveCycle));
                    } else {
                        sleepDwellTicks++;
                    }
                } else {
                    if (inSleep) {
                        logger.info(String.format("FullAppraisal[%s]: WAKE after %d sleep cycles at cycle %d",
                                id, sleepDwellTicks, cognitiveCycle));
                        persist(new SleepEpisodeState(id.key, sleepOnsetCycle, cognitiveCycle, sleepDwellTicks));
                    }
                    inSleep = false;
                    sleepDwellTicks = 0;
                }

                creature.effectorCortex().tell(cortical);

                long inferenceMs = (worldModelFilter != null)
                        ? worldModelFilter.getLastInferenceDurationMs() : 0L;
                ChangeStimulusState change = new ChangeStimulusStateBuilder(this, id)
                        .buildOneReceivedOneEmitted(emotional, cortical);
                ChosenActionState chosenActionState = new ChosenActionState(change,
                        actionSelection.getLastUsedFilterType(), action.type, action.perception.id,
                        inferenceMs);
                persist(change, chosenActionState);
            }
        }
    }

    private CorticalStimulus produceCortical(Action action, double behaviouralEfficiency) {
        double angle, speed, focus;
        CorticalStimulus cortical;
        Random random = new Random();
        Perception perception = action.perception;

        angle = 0;

        focus = Math.max(Constants.MAX_VISION_FIELD_OPENING * behaviouralEfficiency, Constants.MIN_VISION_FIELD_OPENING);
        speed = Math.max(Constants.MAX_STEP * behaviouralEfficiency, Constants.MIN_STEP);

        switch (action.type) {
            case AVOID:
                // 45° offset from target direction (pass by, not head-on)
                angle = perception.angle + Math.PI / 4;
                break;

            case ESCAPE:
                // run directly away from threat
                angle = perception.angle + Math.PI;
                break;

            case APPROACH:
                angle = perception.angle;
                break;

            case EAT:
                speed = 0;
                angle = perception.angle;
                break;

            case WANDER:
                // symmetric random turn ±MAX_ROTATE_ANGLE degrees around current heading
                angle = perception.angle
                        + (2 * random.nextDouble() - 1) * Math.toRadians(Constants.MAX_ROTATE_ANGLE);
                break;

            case OBSERVE:
                speed = 0;
                focus = Constants.MAX_VISION_FIELD_OPENING;
                angle = perception.angle;
                break;

            case SLEEP:
                speed = 0;
                break;
        }

        cortical = new CorticalStimulus(this.id, nextStimulusId(), action.type, action.perception.id, angle, focus, speed);
        return cortical;
    }

    private void dispatchTediumStimulus(ActionType selectedAction) {
        // When the neuromodulator loop is active, tedium is a reward-absence affect regulated by the
        // NeuromodulatorSystem (dopamine relief + serotonin-slowed passive rise), so the legacy
        // action-based tedium drift is suppressed to avoid double-regulation.
        if (learningSettings.isNeuromodulatorLoopActive()) return;
        if (selectedAction == ActionType.SLEEP) return;

        double delta;
        if (selectedAction == ActionType.WANDER) {
            delta = -Constants.TEDIUM_WANDER_RELIEF;
        } else if (selectedAction == ActionType.OBSERVE) {
            delta = Constants.TEDIUM_OBSERVE_RATE;
        } else {
            delta = Constants.TEDIUM_IDLE_RATE;
        }

        creature.homeostatic().tell(
                new TediumStimulus(id, nextStimulusId(), delta, selectedAction));
    }

    // Encode the creature's current homeostatic state into a float[] for the
    // dual-encoder InternalEncoder. Uses internalStateFeatureOrder (e.g.
    // ["ht_hunger","ht_sleep","ht_pain","ht_tedium"]) with "ht_" stripped to
    // get the emotion name. Returns null when no dual encoder is loaded.
    private float[] encodeInternalState() {
        if (worldModelFilter == null || !contract.hasDualEncoder) return null;
        List<String> featureOrder = contract.internalStateFeatureOrder;
        float[] state = new float[featureOrder.size()];
        for (int i = 0; i < featureOrder.size(); i++) {
            String name = featureOrder.get(i).replaceFirst("^ht_", "");
            state[i] = (float) creature.emotions().getLevel(name);
        }
        return state;
    }

    private List<Action> definePossibleActions(List<Perception> perceptions) {
        List<Action> actions = new ArrayList<>();
        for (Perception perception : perceptions) {
            actions.addAll(actionsForPerception(perception));
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
