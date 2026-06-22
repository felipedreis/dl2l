package br.cefetmg.lsi.l2l.analysis.extractor;

import br.cefetmg.lsi.l2l.analysis.dataset.DataSet;
import br.cefetmg.lsi.l2l.common.SequentialId;

import javax.persistence.EntityManager;
import java.util.ArrayList;
import java.util.List;

public class RegulationBatchHistExtractor extends CreatureExtractor {

    public RegulationBatchHistExtractor(EntityManager em, SequentialId id) {
        super(em, id);
    }

    @Override
    public DataSet extract() {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNamedQuery("RegulationBatchStat.countByRegulatingCount")
                .setParameter(1, id.key)
                .getResultList();

        int n = rows.size();
        List<Long> creatureKeys = new ArrayList<>(n);
        List<Integer> regulatingCounts = new ArrayList<>(n);
        List<Long> batches = new ArrayList<>(n);

        for (Object[] row : rows) {
            creatureKeys.add(id.key);
            regulatingCounts.add(((Number) row[0]).intValue());
            batches.add(((Number) row[1]).longValue());
        }

        DataSet ds = new DataSet(n);
        ds.addSeries("creatureKey", creatureKeys.toArray(new Long[0]));
        ds.addSeries("regulatingCount", regulatingCounts.toArray(new Integer[0]));
        ds.addSeries("batches", batches.toArray(new Long[0]));
        return ds;
    }

    @Override
    public String getName() {
        return id + "/reg_hist";
    }
}
