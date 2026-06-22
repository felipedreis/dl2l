package br.cefetmg.lsi.l2l.creature.bd;

import br.cefetmg.lsi.l2l.common.SequentialId;
import br.cefetmg.lsi.l2l.world.WorldObjectType;

import javax.persistence.*;

@Entity
@Table(name="object_seen_state", schema="data")
@NamedNativeQueries({
    @NamedNativeQuery(name = "ObjectSeenState.getPerceptionsByCreature",
        query = "SELECT css.key as creature_key, oss.type as object_type, " +
                "oss.distance, oss.angle, css.time " +
                "FROM data.object_seen_state oss " +
                "JOIN data.change_stimulus_state css ON oss.changestimulusstate_id = css.id " +
                "WHERE css.key = ?"),
    @NamedNativeQuery(name = "ObjectSeenState.getForTrajectory",
        query = "SELECT css.key AS creature_key, css.time, " +
                "oss.key AS object_key, oss.type AS object_type, " +
                "oss.distance, oss.angle, oss.direction " +
                "FROM data.object_seen_state oss " +
                "JOIN data.change_stimulus_state css ON oss.changestimulusstate_id = css.id " +
                "WHERE css.key = ? ORDER BY css.time")
})
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
