package br.cefetmg.lsi.l2l.creature.bd;

import javax.persistence.*;

@Entity
@Table(name="internal_dynamic_state", schema="data")
@NamedNativeQueries({
		@NamedNativeQuery(name="InternalDynamicState.getArousalOverTime",
			query="select TRUNC(((css.time - ?) * ?)::NUMERIC, ?) as _t, " +
					"avg(es.hunger_arausal), avg(es.sleep_arausal) from data.internal_dynamic_state ids " +
					"inner join data.change_stimulus_state css on ids.changestimulusstate_id = css.id " +
					"inner join data.emotional_state es on ids.finalemotionalstate_id = es.id " +
					"where css.key = ? group by _t order by _t")
})
public class InternalDynamicState implements PersistenceState{

	@Id
	@GeneratedValue
	private int id;
	
	@JoinColumn
	@OneToOne(cascade = {CascadeType.ALL})
	private EmotionalState initialEmotionalState;
	
	@JoinColumn
	@OneToOne(cascade = {CascadeType.ALL})
	private EmotionalState finalEmotionalState;
	
	@JoinColumn
	@OneToOne(cascade = {CascadeType.ALL})
	private ChangeStimulusState changeStimulusState;

	public InternalDynamicState() {
		
	}
	
	public InternalDynamicState(EmotionalState initialEmotionalState, 
			EmotionalState finalEmotionalState,
			ChangeStimulusState changeStimulusState){
		
		this.initialEmotionalState = initialEmotionalState;
		this.finalEmotionalState = finalEmotionalState;
		this.changeStimulusState = changeStimulusState;
	}
	
	public EmotionalState getInitialEmotionalState() {
		return initialEmotionalState;
	}

	public void setInitialEmotionalState(EmotionalState initialEmotionalState) {
		this.initialEmotionalState = initialEmotionalState;
	}

	public EmotionalState getFinalEmotionalState() {
		return finalEmotionalState;
	}

	public void setFinalEmotionalState(EmotionalState finalEmotionalState) {
		this.finalEmotionalState = finalEmotionalState;
	}

	public ChangeStimulusState getChangeStimulusState() {
		return changeStimulusState;
	}

	public void setChangeStimulusState(ChangeStimulusState changeStimulusState) {
		this.changeStimulusState = changeStimulusState;
	}
	
}
