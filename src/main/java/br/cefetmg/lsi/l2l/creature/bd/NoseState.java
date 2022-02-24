package br.cefetmg.lsi.l2l.creature.bd;

import br.cefetmg.lsi.l2l.common.SequentialId;

import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.Id;
import javax.persistence.Table;


@Entity
@Table(name="nose_state", schema="data")
public class NoseState implements PersistenceState{
	
	@Id 
	@GeneratedValue
	private int id;
	
	private SequentialId nose;
	
	public NoseState() {
		super();
	}

	public NoseState(SequentialId nose) {
		super();
		this.nose = nose;
	}

	public int getId() {
		return id;
	}

	public void setId(int id) {
		this.id = id;
	}

	public SequentialId getNose() {
		return nose;
	}

	public void setNose(SequentialId nose) {
		this.nose = nose;
	}
}
