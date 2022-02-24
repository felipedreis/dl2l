package br.cefetmg.lsi.l2l.creature.bd;

import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.Id;
import javax.persistence.JoinColumn;
import javax.persistence.NamedQueries;
import javax.persistence.NamedQuery;
import javax.persistence.OneToOne;
import javax.persistence.QueryHint;
import javax.persistence.Table;

/**
 * This class represent the body state in each iteration. Through this data 
 * we can calculate the path taken and the distance traveled by the creature.
 * Each {@link ChangeStimulusState} processed in the {@link br.cefetmg.lsi.l2l.creature.components.Body}
 * produce a BodyState.
 * 
 * @see br.cefetmg.lsi.l2l.creature.components.Body
 * 
 * @author Felipe Duarte dos Reis
 */
@Entity
@Table(name="body_state", schema="data")
@NamedQueries({
	@NamedQuery(name="BodyState.getCreatureTrace", query="select b.initialX, b.initialY, b.finalX, b.finalY from BodyState b " +
            "join b.stimulusState s where s.componentID.key = :keySuper order by s.time asc"),

        @NamedQuery(name="BodyState.getTraveledDistance", query="select sum(b.speed) from BodyState b join b.stimulusState s " +
            "where s.componentID.key = :keysuper",
        hints = {
                @QueryHint(name="eclipselink.query-results-cache", value="true"),
                @QueryHint(name="eclipselink.query-results-cache.size", value="1000"),
                @QueryHint(name="eclipselink.query-results-cache.expiry", value="1800000")
        }
    ),

    @NamedQuery(name="BodyState.getAllTraveledDistance", query="select s.componentID.key, sum(b.speed) from BodyState " +
            "b join b.stimulusState s group by s.componentID.key")
})
public class BodyState implements PersistenceState{
	
	@Id
	@GeneratedValue
	private int id;
	
	private double initialX;
	private double initialY;
	
	private double finalX;
	private double finalY;
	
	private double speed;
	
	@JoinColumn
	@OneToOne
	private ChangeStimulusState stimulusState;
	
	public BodyState(){
		
	}
	
	public BodyState(double initialX, double initialY, double finalX, double finalY, double speed) {
		super();
		
		this.initialX = initialX;
		this.initialY = initialY;
		this.finalX = finalX;
        this.finalY = finalY;
		this.speed = speed;
	}

	public int getId() {
		return id;
	}

	public void setId(int id) {
		this.id = id;
	}

	public double getInitialX() {
		return initialX;
	}

	public void setInitialX(double initialX) {
		this.initialX = initialX;
	}

	public double getInitialY() {
		return initialY;
	}

	public void setInitialY(double initialY) {
		this.initialY = initialY;
	}

	public double getFinalX() {
		return finalX;
	}

	public void setFinalX(double finalX) {
		this.finalX = finalX;
	}

	public double getFinalY() {
		return finalY;
	}

	public void setFinalY(double finalY) {
		this.finalY = finalY;
	}

	public double getSpeed() {
		return speed;
	}
	
	public void setSpeed(double speed) {
		this.speed = speed;
	}
	
	public ChangeStimulusState getStimulusState() {
		return stimulusState;
	}

	public void setStimulusState(ChangeStimulusState stimulusState) {
		this.stimulusState = stimulusState;
	}
	
	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result + id;
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
		BodyState other = (BodyState) obj;
		if (id != other.id)
			return false;
		return true;
	}
}
