package br.cefetmg.lsi.l2l.creature.bd;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import br.cefetmg.lsi.l2l.common.SequentialId;

public class ChangeStimulusState implements PersistenceState{

	private final UUID id = UUID.randomUUID();

	private List<StimulusState> receivedStimuli;

	private List<StimulusState> emittedStimuli;

	private String componentClass;

	private SequentialId componentID;

	private long time;

	public ChangeStimulusState(List<StimulusState> receivedStimuli,
			List<StimulusState> emittedStimuli, String componentClass,
			SequentialId componentID, long time) {
		super();
		this.receivedStimuli = receivedStimuli;
		this.emittedStimuli = emittedStimuli;
		this.componentClass = componentClass;
		this.componentID = componentID;
		this.time = time;
	}

	public long getTime() {
		return time;
	}

	public void setTime(long time) {
		this.time = time;
	}

	public ChangeStimulusState() {
		super();
	}

	public UUID getId() {
		return id;
	}

	public List<StimulusState> getReceivedStimulus() {
		return receivedStimuli;
	}

	public void setReceivedStimulus(List<StimulusState> receivedStimulus) {
		this.receivedStimuli = receivedStimulus;
	}

	public List<StimulusState> getEmittedStimulus() {
		return emittedStimuli;
	}

	public void setEmittedStimulus(List<StimulusState> emittedStimulus) {
		this.emittedStimuli = emittedStimulus;
	}

	public void addEmittedStimulus(StimulusState emittedStimulus) {
		emittedStimulus.setChangeStimulusEmitted(this);
		emittedStimulus.setType(StimulusType.EMITTED);

		if(this.emittedStimuli == null)
			this.emittedStimuli = new ArrayList<StimulusState>();

		this.emittedStimuli.add(emittedStimulus);
	}

	public void addReceivedStimulus(StimulusState receivedStimulus) {
		receivedStimulus.setChangeStimulusReceived(this);
		receivedStimulus.setType(StimulusType.RECEIVED);

		if(this.receivedStimuli == null)
			this.receivedStimuli = new ArrayList<StimulusState>();

		this.receivedStimuli.add(receivedStimulus);
	}

	public String getComponentClass() {
		return componentClass;
	}

	public void setComponentClass(String componentClass) {
		this.componentClass = componentClass;
	}

	public SequentialId getComponentID() {
		return componentID;
	}

	public void setComponentID(SequentialId componentID) {
		this.componentID = componentID;
	}
}
