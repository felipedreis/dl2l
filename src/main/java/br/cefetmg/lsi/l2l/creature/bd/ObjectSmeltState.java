package br.cefetmg.lsi.l2l.creature.bd;

import br.cefetmg.lsi.l2l.common.SequentialId;
import br.cefetmg.lsi.l2l.world.WorldObjectType;

import java.util.UUID;

public class ObjectSmeltState implements PersistenceState {

	private final UUID id = UUID.randomUUID();

	private SequentialId component;

	private WorldObjectType objectType;

	private SmellType smellType;

	private ChangeStimulusState changeStimulusState;

	public ObjectSmeltState() {
		super();
	}

	public ObjectSmeltState(SequentialId component, WorldObjectType type) {
		super();
		this.component = component;
		this.objectType = type;
	}

	public UUID getId() {
		return id;
	}

	public SequentialId getComponent() {
		return component;
	}

	public void setComponent(SequentialId component) {
		this.component = component;
	}

	public ChangeStimulusState getChangeStimulusState() {
		return changeStimulusState;
	}

	public void setChangeStimulusState(ChangeStimulusState changeStimulusState) {
		this.changeStimulusState = changeStimulusState;
	}

	public WorldObjectType getObjectType() {
		return objectType;
	}

	public void setObjectType(WorldObjectType objectType) {
		this.objectType = objectType;
	}

	public SmellType getSmellType() {
		return smellType;
	}

	public void setSmellType(SmellType smellType) {
		this.smellType = smellType;
	}


}
