package br.cefetmg.lsi.l2l.creature.bd;

import java.util.ArrayList;
import java.util.List;

import javax.persistence.*;

import br.cefetmg.lsi.l2l.common.SequentialId;

@Entity
@Table(name="change_stimulus_state", schema="data")
@NamedQueries({
	@NamedQuery(name="ChangeStimulusState.findVerticesByComponentClass", 
			query="select changeStimulus from ChangeStimulusState changeStimulus where changeStimulus.componentClass = :componentClass"), 
	/*
	@NamedQuery(name="ChangeStimulusState.findEdgesOfVertex",
			query="select stimulus.hashIdentfier from StimulusState stimulus inner join stimulus.changeStimulusEmitted changeStimulus where " +
					"stimulus.objectType = br.cefetmg.lsi.l2l.creature.servicesSystem.persistence.model.StimulusType.EMITTED and " +
					"stimulus.changeStimulusEmitted = :vertex"),
					
	@NamedQuery(name="ChangeStimulusState.findVerticesByEdges", 
			query="select changeStimulus from ChangeStimulusState changeStimulus join changeStimulus.receivedStimuli stimulus where " +
					"stimulus.objectType = br.cefetmg.lsi.l2l.creature.servicesSystem.persistence.model.StimulusType.RECEIVED and " +
					"stimulus.hashIdentfier in :edges"),
					
	@NamedQuery(name="ChangeStimulusState.findBackEdgesOfVertex",
			query="select stimulus.hashIdentfier from StimulusState stimulus inner join stimulus.changeStimulusReceived changeStimulus where " +
					"stimulus.objectType = br.cefetmg.lsi.l2l.creature.servicesSystem.persistence.model.StimulusType.RECEIVED and " +
					"stimulus.changeStimulusReceived = :edge"), 
					
	@NamedQuery(name="ChangeStimulusState.findBackVerticesOfEdges", 
			query="select changeStimulus from ChangeStimulusState changeStimulus join changeStimulus.emittedStimuli stimulus where " +
					"stimulus.objectType = br.cefetmg.lsi.l2l.creature.servicesSystem.persistence.model.StimulusType.EMITTED and " +
					"stimulus.hashIdentfier in (:edges)"),
					*/
	@NamedQuery(name="ChangeStimulusState.getGraphSize", 
			query="select max(changeStimulus.id) from ChangeStimulusState changeStimulus")
})

public class ChangeStimulusState implements PersistenceState{
	
	@Id @GeneratedValue
	private int id;
	
	@OneToMany(cascade={CascadeType.ALL}, mappedBy="changeStimulusReceived")
	private List<StimulusState> receivedStimuli;
	
	@OneToMany(cascade={CascadeType.ALL}, mappedBy="changeStimulusEmitted")
	private List<StimulusState> emittedStimuli;
	
	private String componentClass; 
	
	private SequentialId componentID;
	
	private long time;
	
	public ChangeStimulusState(List<StimulusState> receivedStimuli,
			List<StimulusState> emittedStimuli, String componentClass,
			SequentialId componentID, long time) {
		super();
		this.receivedStimuli = receivedStimuli;
		this.emittedStimuli = emittedStimuli;
		this.componentClass = componentClass;
		this.componentID = componentID;
		this.time = time;
	}

	public long getTime() {
		return time;
	}

	public void setTime(long time) {
		this.time = time;
	}

	public ChangeStimulusState() {
		super();
	}

	public int getId() {
		return id;
	}

	public void setId(int id) {
		this.id = id;
	}

	public List<StimulusState> getReceivedStimulus() {
		return receivedStimuli;
	}

	public void setReceivedStimulus(List<StimulusState> receivedStimulus) {
		this.receivedStimuli = receivedStimulus;
	}

	public List<StimulusState> getEmittedStimulus() {
		return emittedStimuli;
	}

	public void setEmittedStimulus(List<StimulusState> emittedStimulus) {
		this.emittedStimuli = emittedStimulus;
	}

	public void addEmittedStimulus(StimulusState emittedStimulus) {
		emittedStimulus.setChangeStimulusEmitted(this);
		emittedStimulus.setType(StimulusType.EMITTED);
		
		if(this.emittedStimuli == null) 
			this.emittedStimuli = new ArrayList<StimulusState>();
		
		this.emittedStimuli.add(emittedStimulus);
	}
	
	public void addReceivedStimulus(StimulusState receivedStimulus) {
		receivedStimulus.setChangeStimulusReceived(this);
		receivedStimulus.setType(StimulusType.RECEIVED);
		
		if(this.receivedStimuli == null) 
			this.receivedStimuli = new ArrayList<StimulusState>();
		
		this.receivedStimuli.add(receivedStimulus);
	}
	
	public String getComponentClass() {
		return componentClass;
	}

	public void setComponentClass(String componentClass) {
		this.componentClass = componentClass;
	}

	public SequentialId getComponentID() {
		return componentID;
	}

	public void setComponentID(SequentialId componentID) {
		this.componentID = componentID;
	}	
}
