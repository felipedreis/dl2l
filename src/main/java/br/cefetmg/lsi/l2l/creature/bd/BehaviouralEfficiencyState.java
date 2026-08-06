package br.cefetmg.lsi.l2l.creature.bd;

import java.util.UUID;

public class BehaviouralEfficiencyState implements PersistenceState {

	private final UUID id = UUID.randomUUID();

	private ChangeStimulusState changeStimulusState;

	private boolean complexTask;

	private double behaviouralEfficiency;

	private int numberOfObjects;

	/**
	 * Issue #85: how many objects were <em>actually</em> perceived this cycle, before
	 * PartialAppraisal's synthetic {@code Self} fallback is applied - so 0 means an empty
	 * sensory field.
	 *
	 * <p>{@link #numberOfObjects} cannot answer that: it counts the post-fallback perception
	 * list, so an empty cycle and a one-real-object cycle both read 1. Measuring the
	 * perception flicker (the fraction of consecutive cycles that cross the empty/non-empty
	 * boundary) previously required reconstructing it from raw stimulus dumps; this column
	 * makes it a one-line query over behavioural_efficiency.
	 */
	private int perceivedObjects;

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

	public int getPerceivedObjects() {
		return perceivedObjects;
	}

	public void setPerceivedObjects(int perceivedObjects) {
		this.perceivedObjects = perceivedObjects;
	}
}
