package br.cefetmg.lsi.l2l.creature.bd;

import javax.persistence.*;
import br.cefetmg.lsi.l2l.common.SequentialId;

@Entity
@Table(name="stimulus_state", schema="data")
@NamedNativeQueries({
		@NamedNativeQuery(name="StimulusState.getProducedStimulusGroupedByTime",
			query="SELECT TRUNC(((t0.TIME - ?) * ?)) as _t, COUNT(t1.ID) FROM data.change_stimulus_state t0, data.stimulus_state t1 " +
					"WHERE (((t0.KEY = ?) AND " +
					"(t1.STIMULUSCLASS = ?)) AND " +
					"(t0.ID = t1.CHANGESTIMULUSEMITTED_ID)) GROUP BY _t "),

		@NamedNativeQuery(name = "StimulusState.countEnvStimuli",
			query = "select  css.key, count(*) from data.stimulus_state ss inner join " +
					"data.change_stimulus_state css on ss.changestimulusemitted_id = css.id " +
					"or ss.changestimulusreceived_id = css.id and " +
					" ss.stimulusclass in ('LuminousStimulus', 'SmellStimulus', 'MechanicalStimulus', 'TouchStimulus', " +
					"'DestructiveStimulus', 'EnergeticStimulus', 'ShockStimulus', 'PlayStimulus', 'ConductiveStimulus')  " +
					"group by css.key")
})
@NamedQueries({
		@NamedQuery(name="StimulusState.getStimulusGroupedByTime",
		query = "select function('TRUNC', (css.time - :bornTime) * :timeConstant) as t, count (ss.id) " +
				"from StimulusState ss join ss.changeStimulusEmitted css where " +
				"css.componentID.key = :key and ss.stimulusClass = :stimulusClass " +
				"group by t ")
})
public class StimulusState implements PersistenceState{
	
	@Id
	@GeneratedValue
	private int id;

	@Embedded
	@AttributeOverrides({
			@AttributeOverride(name="key", column = @Column(name = "componentKey")),
			@AttributeOverride(name="sequential", column = @Column(name = "stimulusSeq"))
	})
	private SequentialId stimulusId;

	@JoinColumn
	@ManyToOne(cascade={CascadeType.ALL}, optional=false)
	private ChangeStimulusState changeStimulusEmitted;
	
	@JoinColumn
	@ManyToOne(cascade={CascadeType.ALL}, optional=false)
	private ChangeStimulusState changeStimulusReceived;
	
	private String stimulusClass;
	
	@Enumerated(EnumType.STRING)
	private StimulusType type;
	
	public StimulusState() {
		
	}
	
	public StimulusState(SequentialId stimulusId, String stimulusClass) {
		super();
		this.stimulusId = stimulusId;
		this.stimulusClass = stimulusClass;
	}

	public int getId() {
		return id;
	}

	public void setId(int code) {
		this.id = code;
	}

	public String getStimulusClass() {
		return stimulusClass;
	}

	public void setStimulusClass(String stimulusClass) {
		this.stimulusClass = stimulusClass;
	}

	public ChangeStimulusState getChangeStimulusEmitted() {
		return changeStimulusEmitted;
	}

	public void setChangeStimulusEmitted(ChangeStimulusState changeStimulusEmitted) {
		this.changeStimulusEmitted = changeStimulusEmitted;
	}

	public ChangeStimulusState getChangeStimulusReceived() {
		return changeStimulusReceived;
	}

	public void setChangeStimulusReceived(ChangeStimulusState changeStimulusReceived) {
		this.changeStimulusReceived = changeStimulusReceived;
	}

	public StimulusType getType() {
		return type;
	}

	public void setType(StimulusType type) {
		this.type = type;
	}

	public SequentialId getStimulusId() {
		return stimulusId;
	}

	public void setStimulusId(SequentialId stimulusId) {
		this.stimulusId = stimulusId;
	}
}
