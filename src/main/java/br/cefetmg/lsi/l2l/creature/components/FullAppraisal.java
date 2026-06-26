package br.cefetmg.lsi.l2l.creature.components;

import br.cefetmg.lsi.l2l.common.Constants;
import br.cefetmg.lsi.l2l.common.SequentialId;
import br.cefetmg.lsi.l2l.creature.actionSelector.ActionProbabilityFilter;
import br.cefetmg.lsi.l2l.creature.actionSelector.ActionSelection;
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
import br.cefetmg.lsi.l2l.stimuli.CorticalStimulus;
import br.cefetmg.lsi.l2l.stimuli.EmotionalStimulus;
import br.cefetmg.lsi.l2l.stimuli.Stimulus;
import br.cefetmg.lsi.l2l.stimuli.TediumStimulus;
import br.cefetmg.lsi.l2l.world.FruitType;
import br.cefetmg.lsi.l2l.world.PlantType;
import br.cefetmg.lsi.l2l.world.Self;
import br.cefetmg.lsi.l2l.world.WorldObjectType;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Created by felipe on 02/01/17.
 */
public class FullAppraisal extends CreatureComponent {

    private ActionSelection actionSelection;

    private MemorySystem memorySystem;

    private WorldModelEngine worldModelEngine;

    private long cognitiveCycle = 0;
    private boolean inSleep = false;
    private int sleepDwellTicks = 0;
    private long sleepOnsetCycle = 0;

    public FullAppraisal(SequentialId id) {
        super(id);
    }

    @Override
    public void preStart() throws Exception {
        super.preStart();
        memorySystem = creature.memory();

        MLServiceExtension.Impl mlExt = MLServiceExtension.of(context().system());
        ModelContract contract = ModelContract.load(mlExt.modelDir());
        worldModelEngine = new WorldModelEngine(mlExt, id.key);

        actionSelection = new ActionSelection(
                new TargetDistanceFilter(),
                new ActionProbabilityFilter(creature.operantConditioning()),
                new WorldModelFilter(worldModelEngine, contract),
                new RandomFilter()
        );
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

            if (stimulus instanceof EmotionalStimulus) {
                cognitiveCycle++;
                memorySystem.tickDecisionCycle();
                EmotionalStimulus emotional = (EmotionalStimulus) stimulus;
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
                        creature.memoryConsolidator().tell(new SleepStarted(cognitiveCycle), self());
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

                creature.effectorCortex().tell(cortical, self());

                ChangeStimulusState change = new ChangeStimulusStateBuilder(this, id)
                        .buildOneReceivedOneEmitted(emotional, cortical);
                ChosenActionState chosenActionState = new ChosenActionState(change,
                        actionSelection.getLastUsedFilterType(), action.type, action.perception.id);
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
                new TediumStimulus(id, nextStimulusId(), delta, selectedAction),
                self());
    }

    private List<Action> definePossibleActions(List<Perception> perceptions) {
        List<Action> actions = new ArrayList<>();


        for (Perception perception : perceptions) {
            if(perception.objectType.isDefined()) {
                WorldObjectType objectType = perception.objectType.get();
                /*
                TODO add a condition to check if the current perception is in the same direction as the creature. If it its, the creature may approach, otherwise one must turn in that angle.
                 */
                if (perception.distance > 0) {
                    if (objectType instanceof FruitType) {
                        actions.add(new Action(ActionType.APPROACH, perception));
                        actions.add(new Action(ActionType.AVOID, perception));
                        actions.add(new Action(ActionType.SLEEP, perception));
                        actions.add(new Action(ActionType.OBSERVE, perception));
                    } else if (objectType instanceof PlantType) {
                        actions.add(new Action(ActionType.APPROACH, perception));
                        actions.add(new Action(ActionType.AVOID, perception));
                        actions.add(new Action(ActionType.SLEEP, perception));
                        actions.add(new Action(ActionType.OBSERVE, perception));
                    }
                } else if (perception.distance == 0) {
                    if (objectType instanceof FruitType) {
                        actions.add(new Action(ActionType.EAT, perception));
                        actions.add(new Action(ActionType.AVOID, perception));
                        actions.add(new Action(ActionType.SLEEP, perception));
                    } else if (objectType instanceof PlantType) {
                        actions.add(new Action(ActionType.EAT, perception));
                        actions.add(new Action(ActionType.AVOID, perception));
                        actions.add(new Action(ActionType.ESCAPE, perception));
                    } else if (objectType instanceof Self) {
                        actions.add(new Action(ActionType.SLEEP, perception));
                        actions.add(new Action(ActionType.WANDER, perception));
                    }
                }
            } else {
                actions.add(new Action(ActionType.SLEEP, perception));
                actions.add(new Action(ActionType.WANDER, perception));
                actions.add(new Action(ActionType.OBSERVE, perception));
            }
        }

        return actions;
    }
}
