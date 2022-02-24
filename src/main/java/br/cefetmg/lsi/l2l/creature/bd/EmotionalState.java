package br.cefetmg.lsi.l2l.creature.bd;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.Id;
import javax.persistence.Table;

@Entity
@Table(name="emotional_state", schema="data")
public class EmotionalState implements PersistenceState {

	@Id
	@GeneratedValue
	private int id;
	
	@Column(name="hunger_arausal")
	private double hunger;
	
	@Column(name="sleep_arausal")
	private double sleep;
	
	@Column(name="apathy_arausal")
	private double apathy;
	
	@Column(name="stress_arausal")
	private double stress;
	
	@Column(name="pain_arausal")
	private double pain;
	
	@Column(name="tedium_arausal")
	private double tedium;
	
	@Column(name="fear_arausal")
	private double fear;
	
	@Column(name="curiosity_arausal")
	private double curiosity;
	
	@Column(name="fertility_arausal")
	private double fertility;
	
	public EmotionalState(){
		
	}
	
	public EmotionalState(double hunger, double sleep, double apathy, double stress, 
			double pain, double tedium, double fear, double curiosity, double fertility) {
		
		this.hunger = hunger;
		this.sleep = sleep;
		this.apathy = apathy;
		this.stress = stress;
		this.pain = pain;
		this.tedium = tedium;
		this.fear = fear;
		this.curiosity = curiosity;
		this.fertility = fertility;
	}

	public int getId() {
		return id;
	}

	public void setId(int id) {
		this.id = id;
	}

	public double getHunger() {
		return hunger;
	}

	public void setHunger(double hunger) {
		this.hunger = hunger;
	}

	public double getSleep() {
		return sleep;
	}

	public void setSleep(double sleep) {
		this.sleep = sleep;
	}

	public double getApathy() {
		return apathy;
	}

	public void setApathy(double apathy) {
		this.apathy = apathy;
	}

	public double getStress() {
		return stress;
	}

	public void setStress(double stress) {
		this.stress = stress;
	}

	public double getPain() {
		return pain;
	}

	public void setPain(double pain) {
		this.pain = pain;
	}

	public double getTedium() {
		return tedium;
	}

	public void setTedium(double tedium) {
		this.tedium = tedium;
	}

	public double getFear() {
		return fear;
	}

	public void setFear(double fear) {
		this.fear = fear;
	}

	public double getCuriosity() {
		return curiosity;
	}

	public void setCuriosity(double curiosity) {
		this.curiosity = curiosity;
	}

	public double getFertility() {
		return fertility;
	}

	public void setFertility(double fertility) {
		this.fertility = fertility;
	}
}
