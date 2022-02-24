package br.cefetmg.lsi.l2l.creature.bd;

import br.cefetmg.lsi.l2l.common.SequentialId;

import java.util.List;

import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.Id;
import javax.persistence.Table;

@Entity
@Table(name="mouth_state", schema="data")
public class MouthState implements PersistenceState {
	
	@Id 
	@GeneratedValue
	private int id;
	
	private SequentialId mouth;
	
	private double radius;
	private double angle;
	private double arcOpening;
	
	private long time;
	
	private List<MouthInteractionState> interactions;
	
	public MouthState(){
		
	}
	
	public MouthState(SequentialId mouth, double radius,
					  double angle, double arcOpening) {
		super();
		this.mouth = mouth;
		this.radius = radius;
		this.angle = angle;
		this.arcOpening = arcOpening;
	}
	
	public int getId() {
		return id;
	}
	
	public void setId(int id) {
		this.id = id;
	}
	
	public SequentialId getMouth() {
		return mouth;
	}
	
	public void setMouth(SequentialId mouth) {
		this.mouth = mouth;
	}
	
	public double getRadius() {
		return radius;
	}
	
	public void setRadius(double radius) {
		this.radius = radius;
	}
	
	public double getAngle() {
		return angle;
	}
	
	public void setAngle(double angle) {
		this.angle = angle;
	}
	
	public double getArcOpening() {
		return arcOpening;
	}
	
	public void setArcOpening(double arcOpening) {
		this.arcOpening = arcOpening;
	}

	public long getTime() {
		return time;
	}

	public void setTime(long time) {
		this.time = time;
	}

	public List<MouthInteractionState> getInteractions() {
		return interactions;
	}

	public void setInteractions(List<MouthInteractionState> interactions) {
		this.interactions = interactions;
	}
}
