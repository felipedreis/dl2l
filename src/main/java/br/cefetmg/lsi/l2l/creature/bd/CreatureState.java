package br.cefetmg.lsi.l2l.creature.bd;

import java.util.UUID;

import br.cefetmg.lsi.l2l.common.SequentialId;

/**
 *
 * @author Felipe Duarte dos Reis
 *
 */
public class CreatureState implements PersistenceState {

	private final UUID id = UUID.randomUUID();

	private SequentialId sequential;

	private SequentialId fatherState;

	private SequentialId motherState;

	private boolean gender;

	private long bornTime;

	private long deadTime;

	public CreatureState () {

	}

	public CreatureState(SequentialId sequential) {
		this.sequential = sequential;
	}

	public CreatureState (SequentialId sequential, boolean gender) {
		this.sequential = sequential;
		this.gender = gender;
	}

	public CreatureState(SequentialId sequential, boolean gender,
			SequentialId fatherState, SequentialId motherState) {
		super();
		this.sequential = sequential;
		this.gender = gender;
		this.fatherState = fatherState;
		this.motherState = motherState;
	}

	public UUID getId() {
		return id;
	}

	public SequentialId getSequential() {
		return sequential;
	}

	public void setSequential(SequentialId sequential) {
		this.sequential = sequential;
	}

	public boolean isGender() {
		return gender;
	}

	public void setGender(boolean genere) {
		this.gender = genere;
	}

	public long getBornTime() {
		return bornTime;
	}

	public void setBornTime(long bornTime) {
		this.bornTime = bornTime;
	}

	public long getDeadTime() {
		return deadTime;
	}

	public void setDeadTime(long deadTime) {
		this.deadTime = deadTime;
	}

	public SequentialId getFatherState() {
		return fatherState;
	}

	public void setFatherState(SequentialId fatherState) {
		this.fatherState = fatherState;
	}

	public SequentialId getMotherState() {
		return motherState;
	}

	public void setMotherState(SequentialId motherState) {
		this.motherState = motherState;
	}

}
