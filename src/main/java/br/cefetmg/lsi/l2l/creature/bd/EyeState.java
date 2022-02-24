package br.cefetmg.lsi.l2l.creature.bd;

import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.Id;
import javax.persistence.JoinColumn;
import javax.persistence.OneToOne;
import javax.persistence.Table;

/**
 * This class represent the eye state in each iteration. Through this data 
 * we can view the area scanned by the creature in the lifetime.
 * Each {@link ChangeStimulusState} processed in the {@link br.cefetmg.lsi.l2l.creature.Eye} 
 * produce a EyeState.
 * 
 * @author Felipe Duarte dos Reis
 *
 */
@Entity
@Table(name="eye_state", schema="data")
public class EyeState implements PersistenceState{
	@Id 
	@GeneratedValue
	private int id;
	
	private double initialStartAngle;
	private double initialOpening;
	

	private double finalStartAngle;
	private double finalOpening;

	
	@JoinColumn
	@OneToOne
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

	public int getId() {
		return id;
	}

	public void setId(int id) {
		this.id = id;
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
		final int prime = 31;
		int result = 1;
		result = prime * result + id;
		return result;
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
		if (id != other.id)
			return false;
		return true;
	}
}
