package br.cefetmg.lsi.l2l.creature.bd;

import java.util.UUID;

import br.cefetmg.lsi.l2l.common.SequentialId;
import br.cefetmg.lsi.l2l.creature.common.ActionType;

public class ChosenActionState implements PersistenceState{

	private final UUID id = UUID.randomUUID();

	private ChangeStimulusState changeStimulusState;

	private ActionSelectionType actionSelectionType;

	private ActionType action;

	private SequentialId target;

	private long inferenceDurationMs;

	public ChosenActionState(){

	}

	public ChosenActionState(ChangeStimulusState changeStimulusState, ActionSelectionType actionSelectionType,
							 ActionType action, SequentialId target) {
		this(changeStimulusState, actionSelectionType, action, target, 0L);
	}

	public ChosenActionState(ChangeStimulusState changeStimulusState, ActionSelectionType actionSelectionType,
							 ActionType action, SequentialId target, long inferenceDurationMs) {
		this.changeStimulusState = changeStimulusState;
		this.actionSelectionType = actionSelectionType;
		this.action = action;
		this.target = target;
		this.inferenceDurationMs = inferenceDurationMs;
	}

	public UUID getId() {
		return id;
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

	public long getInferenceDurationMs() {
		return inferenceDurationMs;
	}

	public void setInferenceDurationMs(long inferenceDurationMs) {
		this.inferenceDurationMs = inferenceDurationMs;
	}

	@Override
	public int hashCode() {
		return id.hashCode();
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
		return id.equals(other.id);
	}
}
