package br.cefetmg.lsi.l2l.creature.bd;

import java.util.UUID;

import br.cefetmg.lsi.l2l.common.SequentialId;

public class StimulusState implements PersistenceState{

	private final UUID id = UUID.randomUUID();

	private SequentialId stimulusId;

	private ChangeStimulusState changeStimulusEmitted;

	private ChangeStimulusState changeStimulusReceived;

	private String stimulusClass;

	private StimulusType type;

	public StimulusState() {

	}

	public StimulusState(SequentialId stimulusId, String stimulusClass) {
		super();
		this.stimulusId = stimulusId;
		this.stimulusClass = stimulusClass;
	}

	public UUID getId() {
		return id;
	}

	public String getStimulusClass() {
		return stimulusClass;
	}

	public void setStimulusClass(String stimulusClass) {
		this.stimulusClass = stimulusClass;
	}

	public ChangeStimulusState getChangeStimulusEmitted() {
		return changeStimulusEmitted;
	}

	public void setChangeStimulusEmitted(ChangeStimulusState changeStimulusEmitted) {
		this.changeStimulusEmitted = changeStimulusEmitted;
	}

	public ChangeStimulusState getChangeStimulusReceived() {
		return changeStimulusReceived;
	}

	public void setChangeStimulusReceived(ChangeStimulusState changeStimulusReceived) {
		this.changeStimulusReceived = changeStimulusReceived;
	}

	public StimulusType getType() {
		return type;
	}

	public void setType(StimulusType type) {
		this.type = type;
	}

	public SequentialId getStimulusId() {
		return stimulusId;
	}

	public void setStimulusId(SequentialId stimulusId) {
		this.stimulusId = stimulusId;
	}
}
