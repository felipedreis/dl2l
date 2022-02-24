package br.cefetmg.lsi.l2l.creature.bd;


import javax.persistence.*;

import br.cefetmg.lsi.l2l.common.SequentialId;

/**
 * 
 * @author Felipe Duarte dos Reis
 *
 */
@Entity
@Table(name="creature_state", schema="data")
@NamedQueries({
		@NamedQuery(name="CreatureState.findAllCreatureIds", query="select c.sequential from CreatureState c"),
		@NamedQuery(name="CreatureState.getLifetimes", query = "select c.sequential, c.deadTime - c.bornTime " +
				"from CreatureState c"),

		@NamedQuery(name="CreatureState.getBornTime",
			query="select c.bornTime from CreatureState c where c.bornTime <> 0 and c.sequential.key = :keysuper",
			hints = {
					@QueryHint(name="eclipselink.query-results-cache", value="true"),
					@QueryHint(name="eclipselink.query-results-cache.size", value="100")
			}
		),

		@NamedQuery(name="CreatureState.getDeadTime",
			query="select c.deadTime from CreatureState c  where c.deadTime <> 0 and c.sequential.key = :keysuper",
			hints = {
					@QueryHint(name="eclipselink.query-results-cache", value="true"),
					@QueryHint(name="eclipselink.query-results-cache.size", value="100")
			}
		),
})
public class CreatureState implements PersistenceState {
	
	@Id 
	@GeneratedValue
	private int id;

	@Embedded 
	@AttributeOverrides({
		@AttributeOverride(name="key", column=@Column(name="creature_key")),
		@AttributeOverride(name="sequential", column=@Column(name="creature_sequential"))
	})		
	private SequentialId sequential;
	
	@Embedded
	@AttributeOverrides({
		@AttributeOverride(name="key", column=@Column(name="father_key")),
		@AttributeOverride(name="sequential", column=@Column(name="father_sequential"))
	})
	private SequentialId fatherState;

	@Embedded 
	@AttributeOverrides({
		@AttributeOverride(name="key", column=@Column(name="mother_key") ),
		@AttributeOverride(name="sequential", column=@Column(name="mother_sequential"))
	})
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

	public int getId() {
		return id;
	}
	
	public void setId(int id) {
		this.id = id;
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