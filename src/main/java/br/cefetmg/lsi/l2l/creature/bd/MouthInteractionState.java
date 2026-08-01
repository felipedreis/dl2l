package br.cefetmg.lsi.l2l.creature.bd;

import br.cefetmg.lsi.l2l.common.SequentialId;

import java.util.UUID;

public class MouthInteractionState implements PersistenceState {
	private final UUID id = UUID.randomUUID();

	private MouthInteractionType type;

	private String objectType;

	private SequentialId objectNumber;

	private ChangeStimulusState changeStimulusState;

	public MouthInteractionState() {

	}

	public MouthInteractionState(MouthInteractionType type,
			String objectType, SequentialId objectNumber,
			ChangeStimulusState changeStimulusState) {
		super();
		this.type = type;
		this.objectType = objectType;
		this.objectNumber = objectNumber;
		this.changeStimulusState = changeStimulusState;
	}

	public UUID getId() {
		return id;
	}

	public MouthInteractionType getType() {
		return type;
	}

	public void setType(MouthInteractionType type) {
		this.type = type;
	}

	public String getObjectType() {
		return objectType;
	}

	public void setObjectType(String objectType) {
		this.objectType = objectType;
	}

	public SequentialId getObjectNumber() {
		return objectNumber;
	}

	public void setObjectNumber(SequentialId objectNumber) {
		this.objectNumber = objectNumber;
	}

	public ChangeStimulusState getChangeStimulusState() {
		return changeStimulusState;
	}

	public void setChangeStimulusState(ChangeStimulusState changeStimulusState) {
		this.changeStimulusState = changeStimulusState;
	}
}
