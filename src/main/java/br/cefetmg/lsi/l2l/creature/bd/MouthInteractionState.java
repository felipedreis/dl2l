package br.cefetmg.lsi.l2l.creature.bd;

import javax.persistence.*;

import br.cefetmg.lsi.l2l.common.SequentialId;
import br.cefetmg.lsi.l2l.world.WorldObjectType;

@Entity
@Table(name="mouth_interactions_state", schema="data")
@NamedQueries({
	@NamedQuery(name="MouthInteractionState.getNumberOfEatenNutrients",
			query="select count(m.id) from MouthInteractionState m join m.changeStimulusState c " +
					"where c.componentID.key = :keysuper and " +
					"m.type = br.cefetmg.lsi.l2l.creature.bd.MouthInteractionType.EAT "),

	@NamedQuery(name="MouthInteractionState.getNutrientInteractions",
			query="select m.objectType from MouthInteractionState m join m.changeStimulusState c " +
					"where c.componentID.key = :keysuper and " +
					"m.type = br.cefetmg.lsi.l2l.creature.bd.MouthInteractionType.EAT order by c.time ASC")
})

@NamedNativeQueries({
    @NamedNativeQuery(name="MouthInteractionState.getEatenNutrientsGroupedOverTime",
			query = "select TRUNC(((css.time - ?)*?)::NUMERIC, ?)  as _t, count(mis.objecttype) " +
					"from data.mouth_interactions_state mis inner join data.change_stimulus_state css on  " +
					"mis.changestimulusstate_id = css.id where mis.objecttype =  ? " +
					"and css.key = ? and mis.type = 'EAT' group by _t order by _t ")
})
public class MouthInteractionState implements PersistenceState {
	@Id @GeneratedValue
	private int id;
	
	@Enumerated(EnumType.STRING)
	private MouthInteractionType type;
	
	@Column
	private String objectType;
	
	private SequentialId objectNumber;
	
	@ManyToOne(cascade={CascadeType.ALL})
	@JoinColumn
	private ChangeStimulusState changeStimulusState;
	
	public MouthInteractionState() {
		
	}
	
	public MouthInteractionState(MouthInteractionType type,
			String objectType, SequentialId objectNumber,
			ChangeStimulusState changeStimulusState) {
		super();
		this.type = type;
		this.objectType = objectType;
		this.objectNumber = objectNumber;
		this.changeStimulusState = changeStimulusState;
	}

	public int getId() {
		return id;
	}

	public void setId(int id) {
		this.id = id;
	}

	public MouthInteractionType getType() {
		return type;
	}

	public void setType(MouthInteractionType type) {
		this.type = type;
	}

	public String getObjectType() {
		return objectType;
	}

	public void setObjectType(String objectType) {
		this.objectType = objectType;
	}

	public SequentialId getObjectNumber() {
		return objectNumber;
	}

	public void setObjectNumber(SequentialId objectNumber) {
		this.objectNumber = objectNumber;
	}

	public ChangeStimulusState getChangeStimulusState() {
		return changeStimulusState;
	}
	
	public void setChangeStimulusState(ChangeStimulusState changeStimulusState) {
		this.changeStimulusState = changeStimulusState;
	}
}
