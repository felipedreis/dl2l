package br.cefetmg.lsi.l2l.creature.bd;

import br.cefetmg.lsi.l2l.common.SequentialId;
import br.cefetmg.lsi.l2l.world.WorldObjectType;

import javax.persistence.*;

@Entity
@Table(name="object_seen_state", schema="data")
public class ObjectSeenState implements PersistenceState{
	@Id 
	@GeneratedValue
	private int id;

	@Column
	@Lob
	private WorldObjectType type;

	private SequentialId objectNumber;

	@JoinColumn
	@OneToOne(cascade = {CascadeType.ALL})
	private ChangeStimulusState changeStimulusState;
	
	private double distance;
	private double angle;
	private double direction;
	
	public ObjectSeenState() {
		super();
	}

	public ObjectSeenState(ChangeStimulusState state, WorldObjectType type,
						   SequentialId objectNumber, double distance, double angle,
						   double direction) {
		super();
		this.changeStimulusState = state;
		this.type = type;
		this.objectNumber = objectNumber;
		this.distance = distance;
		this.angle = angle;
		this.direction = direction;
	}

	public int getId() {
		return id;
	}

	public void setId(int id) {
		this.id = id;
	}

	public WorldObjectType getType() {
		return type;
	}

	public void setType(WorldObjectType type) {
		this.type = type;
	}

	public SequentialId getObjectNumber() {
		return objectNumber;
	}

	public void setObjectNumber(SequentialId objectNumber) {
		this.objectNumber = objectNumber;
	}

	public double getDistance() {
		return distance;
	}

	public void setDistance(double distance) {
		this.distance = distance;
	}

	public double getAngle() {
		return angle;
	}

	public void setAngle(double angle) {
		this.angle = angle;
	}

	public double getDirection() {
		return direction;
	}

	public void setDirection(double direction) {
		this.direction = direction;
	}
	
	public ChangeStimulusState getChangeStimulusState() {
		return changeStimulusState;
	}
	
	public void setChangeStimulusState(ChangeStimulusState changeStimulusState) {
		this.changeStimulusState = changeStimulusState;
	}
}
