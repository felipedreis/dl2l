package br.cefetmg.lsi.l2l.creature.bd;

import br.cefetmg.lsi.l2l.common.SequentialId;
import br.cefetmg.lsi.l2l.world.WorldObjectType;

import javax.persistence.*;


@Entity
@Table(name="object_smelt_state", schema="data")
public class ObjectSmeltState implements PersistenceState {

	@Id 
	@GeneratedValue
	private int id;

	@Embedded
	private SequentialId component;
	
	@Column
	@Lob
	private WorldObjectType objectType;

	@Enumerated(EnumType.STRING)
	private SmellType smellType;
	
	@ManyToOne(cascade={CascadeType.ALL})
	@JoinColumn
	private ChangeStimulusState changeStimulusState;
	
	public ObjectSmeltState() {
		super();
	}

	public ObjectSmeltState(SequentialId component, WorldObjectType type) {
		super();
		this.component = component;
		this.objectType = type;
	}

	public int getId() {
		return id;
	}

	public void setId(int id) {
		this.id = id;
	}

	public SequentialId getComponent() {
		return component;
	}

	public void setComponent(SequentialId component) {
		this.component = component;
	}

	public ChangeStimulusState getChangeStimulusState() {
		return changeStimulusState;
	}

	public void setChangeStimulusState(ChangeStimulusState changeStimulusState) {
		this.changeStimulusState = changeStimulusState;
	}

	public WorldObjectType getObjectType() {
		return objectType;
	}

	public void setObjectType(WorldObjectType objectType) {
		this.objectType = objectType;
	}

	public SmellType getSmellType() {
		return smellType;
	}

	public void setSmellType(SmellType smellType) {
		this.smellType = smellType;
	}
	
	
}
