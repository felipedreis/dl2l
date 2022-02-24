package br.cefetmg.lsi.l2l.creature.bd;

import javax.persistence.*;

@Entity
@Table(name="memory_evocation_state", schema="data")
@NamedQueries({
	@NamedQuery(name="MemoryEvocationState.getMemoriesByCreature", query="select distinct m.memoryNumber from MemoryEvocationState m join m.changeStimulusState c where c.componentID.key = :keySuper"),
	@NamedQuery(name="MemoryEvocationState.findEvocationsByCreature", query="select m.intensity from MemoryEvocationState m join m.changeStimulusState c where c.componentID.key = :keySuper order by c.time")
})
public class MemoryEvocationState implements PersistenceState {
	
	@Id
	@GeneratedValue
	private int id;
	
	private int memoryNumber;
	
	private String target;
	
	private double intensity;
	
	private boolean traumatic;
	
	@JoinColumn
	@OneToOne(cascade = {CascadeType.ALL})
	private ChangeStimulusState changeStimulusState;

	public MemoryEvocationState(){
		
	}

	public MemoryEvocationState(int memoryNumber, String target,
			double intensity, boolean traumatic,
			ChangeStimulusState changeStimulusState) {
		super();
		this.memoryNumber = memoryNumber;
		this.target = target;
		this.intensity = intensity;
		this.traumatic = traumatic;
		this.changeStimulusState = changeStimulusState;
	}

	public int getId() {
		return id;
	}

	public void setId(int id) {
		this.id = id;
	}

	public int getMemoryNumber() {
		return memoryNumber;
	}

	public void setMemoryNumber(int memoryNumber) {
		this.memoryNumber = memoryNumber;
	}
	
	public String getTarget() {
		return target;
	}

	public void setTarget(String target) {
		this.target = target;
	}

	public double getIntensity() {
		return intensity;
	}

	public void setIntensity(double intensity) {
		this.intensity = intensity;
	}

	public ChangeStimulusState getChangeStimulusState() {
		return changeStimulusState;
	}

	public void setChangeStimulusState(ChangeStimulusState changeStimulusState) {
		this.changeStimulusState = changeStimulusState;
	}

	public boolean isTraumatic() {
		return traumatic;
	}

	public void setTraumatic(boolean traumatic) {
		this.traumatic = traumatic;
	}

	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime
				* result
				+ ((changeStimulusState == null) ? 0 : changeStimulusState
						.hashCode());
		result = prime * result + id;
		long temp;
		temp = Double.doubleToLongBits(intensity);
		result = prime * result + (int) (temp ^ (temp >>> 32));
		result = prime * result + memoryNumber;
		result = prime * result + ((target == null) ? 0 : target.hashCode());
		result = prime * result + (traumatic ? 1231 : 1237);
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
		MemoryEvocationState other = (MemoryEvocationState) obj;
		if (changeStimulusState == null) {
			if (other.changeStimulusState != null)
				return false;
		} else if (!changeStimulusState.equals(other.changeStimulusState))
			return false;
		if (id != other.id)
			return false;
		if (Double.doubleToLongBits(intensity) != Double
				.doubleToLongBits(other.intensity))
			return false;
		if (memoryNumber != other.memoryNumber)
			return false;
		if (target == null) {
			if (other.target != null)
				return false;
		} else if (!target.equals(other.target))
			return false;
		if (traumatic != other.traumatic)
			return false;
		return true;
	}

	@Override
	public String toString() {
		return "MemeoryEvocationState [memoryNumber=" + memoryNumber + "]";
	}
}
