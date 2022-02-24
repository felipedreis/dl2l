package br.cefetmg.lsi.l2l.analysis.extractor;

import javax.persistence.EntityManager;

import br.cefetmg.lsi.l2l.common.SequentialId;

/**
 * Created by felipe on 11/12/15.
 */
public abstract class CreatureExtractor extends Extractor {
	protected SequentialId id;
    
	public CreatureExtractor(EntityManager em, SequentialId creatureId) {
		super(em);
		
		id = creatureId;
	}
	
}
