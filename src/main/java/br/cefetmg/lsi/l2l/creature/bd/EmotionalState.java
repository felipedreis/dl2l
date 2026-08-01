package br.cefetmg.lsi.l2l.creature.bd;

import java.util.UUID;

public class EmotionalState implements PersistenceState {

	private final UUID id = UUID.randomUUID();

	private double hunger;
	private double sleep;
	private double apathy;
	private double stress;
	private double pain;
	private double tedium;
	private double fear;
	private double curiosity;
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

	public UUID getId() {
		return id;
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
