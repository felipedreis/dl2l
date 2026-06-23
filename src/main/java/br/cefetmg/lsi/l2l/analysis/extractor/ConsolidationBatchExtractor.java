package br.cefetmg.lsi.l2l.analysis.extractor;

import br.cefetmg.lsi.l2l.analysis.dataset.DataSet;
import br.cefetmg.lsi.l2l.common.SequentialId;

import javax.persistence.EntityManager;
import java.util.ArrayList;
import java.util.List;

/**
 * Exports one row per training batch within every sleep episode.
 * Produces columns: creature_key, onset_cycle, batch_index, batch_size, loss.
 * Ordered by onset_cycle then batch_index — ready for per-episode loss-curve plots.
 */
public class ConsolidationBatchExtractor extends CreatureExtractor {

    public ConsolidationBatchExtractor(EntityManager em, SequentialId id) {
        super(em, id);
    }

    @Override
    public DataSet extract() {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNamedQuery("ConsolidationBatchStat.getForCreature")
                .setParameter(1, id.key)
                .getResultList();

        int n = rows.size();
        List<Long>    creatureKeys = new ArrayList<>(n);
        List<Long>    onsetCycles  = new ArrayList<>(n);
        List<Integer> batchIndexes = new ArrayList<>(n);
        List<Integer> batchSizes   = new ArrayList<>(n);
        List<Float>   losses       = new ArrayList<>(n);

        for (Object[] row : rows) {
            creatureKeys.add(((Number) row[0]).longValue());
            onsetCycles .add(((Number) row[1]).longValue());
            batchIndexes.add(((Number) row[2]).intValue());
            batchSizes  .add(((Number) row[3]).intValue());
            losses      .add(((Number) row[4]).floatValue());
        }

        DataSet ds = new DataSet(n);
        ds.addSeries("creature_key", creatureKeys.toArray(new Long[0]));
        ds.addSeries("onset_cycle",  onsetCycles .toArray(new Long[0]));
        ds.addSeries("batch_index",  batchIndexes.toArray(new Integer[0]));
        ds.addSeries("batch_size",   batchSizes  .toArray(new Integer[0]));
        ds.addSeries("loss",         losses      .toArray(new Float[0]));
        return ds;
    }

    @Override
    public String getName() {
        return id + "/consolidation_batches";
    }
}
