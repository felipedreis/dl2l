package br.cefetmg.lsi.l2l.analysis.extractor;

import br.cefetmg.lsi.l2l.analysis.dataset.DataSet;
import br.cefetmg.lsi.l2l.common.SequentialId;

import javax.persistence.EntityManager;
import java.math.BigInteger;

public class RegulationBatchCollisionsExtractor extends CreatureExtractor {

    public RegulationBatchCollisionsExtractor(EntityManager em, SequentialId id) {
        super(em, id);
    }

    @Override
    public DataSet extract() {
        Object result = em.createNamedQuery("RegulationBatchStat.sameDriveCollisions")
                .setParameter(1, id.key)
                .getSingleResult();

        long collisions = ((BigInteger) result).longValue();

        DataSet ds = new DataSet(1);
        ds.addSeries("creatureKey", new Long[]{ id.key });
        ds.addSeries("sameDriveCollisions", new Long[]{ collisions });
        return ds;
    }

    @Override
    public String getName() {
        return id + "/reg_collisions";
    }
}
