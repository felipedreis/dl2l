package br.cefetmg.lsi.l2l.creature.bd;

import javax.persistence.*;


@Entity
@Table(name="behavioural_efficiency_state", schema="data")
@NamedNativeQueries({
        @NamedNativeQuery(name="BehaviouralEfficiencyState.getBehaviouralEfficiency",
                query = "SELECT ROUND(CAST((css.time - ?) * ? AS NUMERIC),?) as t, " +
                        " avg(bes.behaviouralefficiency), count(bes.behaviouralefficiency) as efficiency " +
                        " FROM data.behavioural_efficiency_state bes " +
                        " inner join data.change_stimulus_state css on bes.changestimulusstate_id = css.id " +
                        " where css.key = ? and bes.complextask = ? group by t order by t asc "
        )
})
public class BehaviouralEfficiencyState implements PersistenceState {
	
	@Id
	@GeneratedValue
	private int id;
	
	@OneToOne(cascade = {CascadeType.ALL})
	@JoinColumn
	private ChangeStimulusState changeStimulusState;
	
	private boolean complexTask;
	
	private double behaviouralEfficiency;
	
	private int numberOfObjects;
	
	public BehaviouralEfficiencyState(){
		
	}

	public int getId() {
		return id;
	}

	public void setId(int id) {
		this.id = id;
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
