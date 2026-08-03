package br.cefetmg.lsi.l2l.creature.bd;

import java.util.UUID;

public class BehaviouralEfficiencyState implements PersistenceState {

	private final UUID id = UUID.randomUUID();

	private ChangeStimulusState changeStimulusState;

	private boolean complexTask;

	private double behaviouralEfficiency;

	private int numberOfObjects;

	public BehaviouralEfficiencyState(){

	}

	public UUID getId() {
		return id;
	}

	public ChangeStimulusState getChangeStimulusState() {
		return changeStimulusState;
	}

	public void setChangeStimulusState(ChangeStimulusState changeStimulusState) {
		this.changeStimulusState = changeStimulusState;
	}

	public boolean isComplexTask() {
		return complexTask;
	}

	public void setComplexTask(boolean complexTask) {
		this.complexTask = complexTask;
	}

	public double getBehaviouralEfficiency() {
		return behaviouralEfficiency;
	}

	public void setBehaviouralEfficiency(double behaviouralEfficiency) {
		this.behaviouralEfficiency = behaviouralEfficiency;
	}

	public int getNumberOfObjects() {
		return numberOfObjects;
	}

	public void setNumberOfObjects(int numberOfObjects) {
		this.numberOfObjects = numberOfObjects;
	}
}
