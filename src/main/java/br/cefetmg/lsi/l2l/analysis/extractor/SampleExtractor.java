package br.cefetmg.lsi.l2l.analysis.extractor;

import java.util.List;

import javax.persistence.EntityManager;

import br.cefetmg.lsi.l2l.common.SequentialId;

/**
 * Created by felipe on 11/12/15.
 */
public abstract class SampleExtractor extends Extractor {
	protected List<SequentialId> ids;
    
	public SampleExtractor(EntityManager em, List<SequentialId> creaturesIds) {
		super(em);
		
		ids = creaturesIds;
	}
	
}
