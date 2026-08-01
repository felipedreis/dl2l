package br.cefetmg.lsi.l2l.creature.bd;

import br.cefetmg.lsi.l2l.common.SequentialId;

import java.util.UUID;

public class NoseState implements PersistenceState{

	private final UUID id = UUID.randomUUID();

	private SequentialId nose;

	public NoseState() {
		super();
	}

	public NoseState(SequentialId nose) {
		super();
		this.nose = nose;
	}

	public UUID getId() {
		return id;
	}

	public SequentialId getNose() {
		return nose;
	}

	public void setNose(SequentialId nose) {
		this.nose = nose;
	}
}
