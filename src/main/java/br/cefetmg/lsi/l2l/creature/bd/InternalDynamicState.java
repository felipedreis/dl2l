package br.cefetmg.lsi.l2l.creature.bd;

import java.util.UUID;

public class InternalDynamicState implements PersistenceState{

	private final UUID id = UUID.randomUUID();

	private EmotionalState initialEmotionalState;

	private EmotionalState finalEmotionalState;

	private ChangeStimulusState changeStimulusState;

	public InternalDynamicState() {

	}

	public InternalDynamicState(EmotionalState initialEmotionalState,
			EmotionalState finalEmotionalState,
			ChangeStimulusState changeStimulusState){

		this.initialEmotionalState = initialEmotionalState;
		this.finalEmotionalState = finalEmotionalState;
		this.changeStimulusState = changeStimulusState;
	}

	public UUID getId() {
		return id;
	}

	public EmotionalState getInitialEmotionalState() {
		return initialEmotionalState;
	}

	public void setInitialEmotionalState(EmotionalState initialEmotionalState) {
		this.initialEmotionalState = initialEmotionalState;
	}

	public EmotionalState getFinalEmotionalState() {
		return finalEmotionalState;
	}

	public void setFinalEmotionalState(EmotionalState finalEmotionalState) {
		this.finalEmotionalState = finalEmotionalState;
	}

	public ChangeStimulusState getChangeStimulusState() {
		return changeStimulusState;
	}

	public void setChangeStimulusState(ChangeStimulusState changeStimulusState) {
		this.changeStimulusState = changeStimulusState;
	}

}
