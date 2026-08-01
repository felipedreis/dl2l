package br.cefetmg.lsi.l2l.creature.bd;

import java.util.UUID;

/**
 * This class represent the body state in each iteration. Through this data
 * we can calculate the path taken and the distance traveled by the creature.
 * Each {@link ChangeStimulusState} processed in the {@link br.cefetmg.lsi.l2l.creature.components.Body}
 * produce a BodyState.
 *
 * @see br.cefetmg.lsi.l2l.creature.components.Body
 *
 * @author Felipe Duarte dos Reis
 */
public class BodyState implements PersistenceState{

	private final UUID id = UUID.randomUUID();

	private double initialX;
	private double initialY;

	private double finalX;
	private double finalY;

	private double speed;

	private ChangeStimulusState stimulusState;

	public BodyState(){

	}

	public BodyState(double initialX, double initialY, double finalX, double finalY, double speed) {
		super();

		this.initialX = initialX;
		this.initialY = initialY;
		this.finalX = finalX;
        this.finalY = finalY;
		this.speed = speed;
	}

	public UUID getId() {
		return id;
	}

	public double getInitialX() {
		return initialX;
	}

	public void setInitialX(double initialX) {
		this.initialX = initialX;
	}

	public double getInitialY() {
		return initialY;
	}

	public void setInitialY(double initialY) {
		this.initialY = initialY;
	}

	public double getFinalX() {
		return finalX;
	}

	public void setFinalX(double finalX) {
		this.finalX = finalX;
	}

	public double getFinalY() {
		return finalY;
	}

	public void setFinalY(double finalY) {
		this.finalY = finalY;
	}

	public double getSpeed() {
		return speed;
	}

	public void setSpeed(double speed) {
		this.speed = speed;
	}

	public ChangeStimulusState getStimulusState() {
		return stimulusState;
	}

	public void setStimulusState(ChangeStimulusState stimulusState) {
		this.stimulusState = stimulusState;
	}

	@Override
	public int hashCode() {
		return id.hashCode();
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		BodyState other = (BodyState) obj;
		return id.equals(other.id);
	}
}
