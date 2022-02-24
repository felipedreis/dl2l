package br.cefetmg.lsi.l2l.creature.bd;

import br.cefetmg.lsi.l2l.common.SequentialId;
import br.cefetmg.lsi.l2l.creature.components.CreatureComponent;
import br.cefetmg.lsi.l2l.stimuli.Stimulus;

import java.util.List;

/**
 * Created by felipe on 02/08/17.
 */
public class ChangeStimulusStateBuilder {

    private CreatureComponent component;
    private SequentialId objectSequentialNumber;

    public ChangeStimulusStateBuilder (CreatureComponent component, SequentialId objectSequentialNumber) {
        this.component = component;
        this.objectSequentialNumber = objectSequentialNumber;
    }

    public ChangeStimulusState buildOneReceivedOneEmitted(Stimulus received, Stimulus emitted) {

        ChangeStimulusState changeStimulusState = new ChangeStimulusState();
        StimulusState emittedStimulusState = null;
        StimulusState receivedStimulusState = null;

        changeStimulusState.setComponentClass(component.getClass().getSimpleName());
        changeStimulusState.setComponentID(objectSequentialNumber);
        changeStimulusState.setTime(System.currentTimeMillis());

        if (received != null) {
            receivedStimulusState = new StimulusState(received.stimulusId, received.getClass().getSimpleName());
            changeStimulusState.addReceivedStimulus(receivedStimulusState);
        }

        if(emitted != null) {
            emittedStimulusState = new StimulusState(emitted.stimulusId, emitted.getClass().getSimpleName());
            changeStimulusState.addEmittedStimulus(emittedStimulusState);
        }

        return changeStimulusState;
    }

    public ChangeStimulusState buildMultipleReceivedMultipleEmitted(List<Stimulus> received, List<Stimulus> emitted) {

        ChangeStimulusState changeStimulusState = new ChangeStimulusState();
        StimulusState emittedStimulusState = null;
        StimulusState receivedStimulusState = null;

        changeStimulusState.setComponentClass(component.getClass().getSimpleName());
        changeStimulusState.setComponentID(objectSequentialNumber);
        changeStimulusState.setTime(System.currentTimeMillis());

        for(Stimulus receivedStumulus : received) {
            if(receivedStumulus != null) {
                receivedStimulusState = new StimulusState(receivedStumulus.stimulusId,
                        receivedStumulus.getClass().getSimpleName());
                changeStimulusState.addReceivedStimulus(receivedStimulusState);
            }
        }

        for(Stimulus emittedStimulus : emitted) {

            if(emittedStimulus != null) {

                emittedStimulusState = new StimulusState(emittedStimulus.stimulusId,
                        emittedStimulus.getClass().getSimpleName());

                changeStimulusState.addEmittedStimulus(emittedStimulusState);
            }
        }

        return changeStimulusState;
    }

    public ChangeStimulusState buildMultipleReceivedOneEmitted(List<Stimulus> received, Stimulus emitted)
    {
        ChangeStimulusState changeStimulusState = new ChangeStimulusState();
        StimulusState emittedStimulusState = null;
        StimulusState receivedStimulusState = null;

        changeStimulusState.setComponentClass(component.getClass().getSimpleName());
        changeStimulusState.setComponentID(objectSequentialNumber);
        changeStimulusState.setTime(System.currentTimeMillis());

        for(Stimulus receivedStumulus : received) {
            if(receivedStumulus != null) {
                receivedStimulusState = new StimulusState(receivedStumulus.stimulusId,
                        receivedStumulus.getClass().getSimpleName());
                changeStimulusState.addReceivedStimulus(receivedStimulusState);
            }
        }

        if(emitted != null){
            emittedStimulusState = new StimulusState(emitted.stimulusId, emitted.getClass().getSimpleName());
            changeStimulusState.addEmittedStimulus(emittedStimulusState);
        }

        return changeStimulusState;
    }

    public ChangeStimulusState buildOneReceivedMultipleEmitted(Stimulus received, List<Stimulus> emitted) {

        ChangeStimulusState changeStimulusState = new ChangeStimulusState();
        StimulusState emittedStimulusState = null;
        StimulusState receivedStimulusState = null;

        changeStimulusState.setComponentClass(component.getClass().getSimpleName());
        changeStimulusState.setComponentID(objectSequentialNumber);
        changeStimulusState.setTime(System.currentTimeMillis());

        if (received != null) {
            receivedStimulusState = new StimulusState(received.stimulusId, received.getClass().getSimpleName());
            changeStimulusState.addReceivedStimulus(receivedStimulusState);
        }

        for(Stimulus emittedStimulus : emitted) {
            if (emittedStimulus != null) {
                emittedStimulusState = new StimulusState(emittedStimulus.stimulusId,
                        emittedStimulus.getClass().getSimpleName());
                changeStimulusState.addEmittedStimulus(emittedStimulusState);
            }
        }

        return changeStimulusState;
    }

}
