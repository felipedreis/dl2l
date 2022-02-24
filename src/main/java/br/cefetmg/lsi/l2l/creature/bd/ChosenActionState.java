package br.cefetmg.lsi.l2l.creature.bd;

import javax.persistence.*;

import br.cefetmg.lsi.l2l.common.SequentialId;
import br.cefetmg.lsi.l2l.creature.common.ActionType;

@Entity
@Table(name="chosen_action_state", schema="data")
@NamedQueries({
	@NamedQuery(name="ChosenActionState.countSelectionsByType", 
			query="select count(chosen.id) from ChosenActionState chosen join chosen.changeStimulusState change where " +
                    "chosen.actionSelectionType = :type and change.componentID.key = :keysuper"),
	
	@NamedQuery(name="ChosenActionState.getInteractionsByType",
			query="select interaction.objectType from MouthInteractionState interaction, " +
					" ChosenActionState chosen join chosen.changeStimulusState change " +
					" where change.componentID.key = :keysuper and chosen.target = interaction.objectNumber " +
					" and chosen.action in (\"eat\", \"play\", \"touch\") and chosen.actionSelectionType = :type " +
                    " order by change.time asc"),

	@NamedQuery(name="ChosenActionState.getSelectionsByType", 
			query="select chosen.actionSelectionType from ChosenActionState chosen " +
                    " join chosen.changeStimulusState change where change.componentID.key = :keysuper " +
                    " and chosen.action in (\"eat\", \"play\", \"touch\") " +
					" order by change.time asc"),


    @NamedQuery(name="ChosenActionState.getInteractionsOverTime",
        query="select (change.time - :bornTime), interaction.objectType from MouthInteractionState interaction, " +
                " ChosenActionState chosen join chosen.changeStimulusState change " +
                " where change.componentID.key = :keysuper and chosen.target = interaction.objectNumber " +
                " and chosen.action in (\"eat\", \"play\", \"touch\") and chosen.actionSelectionType = :actionSelectionType " +
                " order by change.time asc"),

    @NamedQuery(name="ChosenActionState.getSelectionsOverTime",
            query="select (change.time - :bornTime), chosen.actionSelectionType from ChosenActionState chosen " +
                    " join chosen.changeStimulusState change where change.componentID.key = :keysuper " +
                    " and chosen.action in :actions " +
                    " order by change.time asc"),
})
@NamedNativeQueries({
		@NamedNativeQuery(name="ChosenActionState.getSelectionsGroupedOverTime",
			query = " select ROUND(((css.time - ?) * ?)::NUMERIC,?) as _t, count(*) " +
					" from data.chosen_action_state chosen inner join " +
					" data.change_stimulus_state css on chosen.changestimulusstate_id = css.id where css.key = ? " +
					" and chosen.action in ('EAT', 'PLAY', 'TOUCH') and " +
					" chosen.actionSelectionType = ? group by _t order by _t asc")
})
public class ChosenActionState implements PersistenceState{
	
	@Id
	@GeneratedValue
	private int id;

	@OneToOne(cascade = {CascadeType.ALL})
	@JoinColumn
	private ChangeStimulusState changeStimulusState;

	@Enumerated(EnumType.STRING)
	private ActionSelectionType actionSelectionType;

	@Enumerated(EnumType.STRING)
	private ActionType action;

	@Embedded
	private SequentialId target;
	
	public ChosenActionState(){
		
	}

	public ChosenActionState(ChangeStimulusState changeStimulusState, ActionSelectionType actionSelectionType,
							 ActionType action, SequentialId target) {
		this.changeStimulusState = changeStimulusState;
		this.actionSelectionType = actionSelectionType;
		this.action = action;
		this.target = target;
	}

	public int getId() {
		return id;
	}

	public void setId(int id) {
		this.id = id;
	}

	public ActionSelectionType getActionSelectionType() {
		return actionSelectionType;
	}

	public void setActionSelectionType(ActionSelectionType actionSelectionType) {
		this.actionSelectionType = actionSelectionType;
	}

	public ActionType getAction() {
		return action;
	}

	public void setAction(ActionType action) {
		this.action = action;
	}

	public ChangeStimulusState getChangeStimulusState() {
		return changeStimulusState;
	}

	public void setChangeStimulusState(ChangeStimulusState changeStimulusState) {
		this.changeStimulusState = changeStimulusState;
	}
	
	public SequentialId getTarget() {
		return target;
	}
	
	public void setTarget(SequentialId target) {
		this.target = target;
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
		ChosenActionState other = (ChosenActionState) obj;
		if (id != other.id)
			return false;
		return true;
	}
}
