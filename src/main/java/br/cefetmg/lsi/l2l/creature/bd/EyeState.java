package br.cefetmg.lsi.l2l.creature.bd;

import java.util.UUID;

/**
 * This class represent the eye state in each iteration. Through this data
 * we can view the area scanned by the creature in the lifetime.
 * Each {@link ChangeStimulusState} processed in the {@link br.cefetmg.lsi.l2l.creature.Eye}
 * produce a EyeState.
 *
 * @author Felipe Duarte dos Reis
 *
 */
public class EyeState implements PersistenceState{
	private final UUID id = UUID.randomUUID();

	private double initialStartAngle;
	private double initialOpening;


	private double finalStartAngle;
	private double finalOpening;


	private ChangeStimulusState changeStimulusState;

	public EyeState() {

	}

	public EyeState(ChangeStimulusState changeStimulus, double initialStartAngle, double initialOpening,
					double finalStartAngle, double finalOpening) {
		super();
		this.initialStartAngle = initialStartAngle;
		this.initialOpening = initialOpening;
		this.finalStartAngle = finalStartAngle;
		this.finalOpening = finalOpening;
		this.changeStimulusState = changeStimulus;
	}

	public UUID getId() {
		return id;
	}

	public double getInitialStartAngle() {
		return initialStartAngle;
	}

	public void setInitialStartAngle(double initialStartAngle) {
		this.initialStartAngle = initialStartAngle;
	}

	public double getInitialOpening() {
		return initialOpening;
	}

	public void setInitialOpening(double initialOpening) {
		this.initialOpening = initialOpening;
	}

	public double getFinalStartAngle() {
		return finalStartAngle;
	}

	public void setFinalStartAngle(double finalStartAngle) {
		this.finalStartAngle = finalStartAngle;
	}

	public double getFinalOpening() {
		return finalOpening;
	}

	public void setFinalOpening(double finalOpening) {
		this.finalOpening = finalOpening;
	}

	public ChangeStimulusState getChangeStimulusState() {
		return changeStimulusState;
	}

	public void setChangeStimulusState(ChangeStimulusState changeStimulus) {
		this.changeStimulusState = changeStimulus;
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
		EyeState other = (EyeState) obj;
		return id.equals(other.id);
	}
}
