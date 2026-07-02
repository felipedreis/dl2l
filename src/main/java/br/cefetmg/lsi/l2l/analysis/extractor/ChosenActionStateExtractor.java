package br.cefetmg.lsi.l2l.analysis.extractor;

import br.cefetmg.lsi.l2l.analysis.dataset.DataSet;
import br.cefetmg.lsi.l2l.common.SequentialId;

import javax.persistence.EntityManager;
import java.util.ArrayList;
import java.util.List;

public class ChosenActionStateExtractor extends CreatureExtractor {

    public ChosenActionStateExtractor(EntityManager em, SequentialId id) {
        super(em, id);
    }

    @Override
    public DataSet extract() {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNamedQuery("ChosenActionState.getForTrajectory")
                .setParameter(1, id.key)
                .getResultList();

        int n = rows.size();
        List<Long>   creatureKeys     = new ArrayList<>(n);
        List<Long>   actionTimes      = new ArrayList<>(n);
        List<String> actionTypes      = new ArrayList<>(n);
        List<String> selectionTypes   = new ArrayList<>(n);
        List<Long>   targetKeys       = new ArrayList<>(n);
        List<Long>   inferenceDurations = new ArrayList<>(n);

        for (Object[] row : rows) {
            creatureKeys    .add(((Number) row[0]).longValue());
            actionTimes     .add(((Number) row[1]).longValue());
            actionTypes     .add(String.valueOf(row[2]));
            selectionTypes  .add(String.valueOf(row[3]));
            targetKeys      .add(((Number) row[4]).longValue());
            inferenceDurations.add(row[5] != null ? ((Number) row[5]).longValue() : 0L);
        }

        DataSet ds = new DataSet(n);
        ds.addSeries("creatureKey",         creatureKeys    .toArray(new Long[0]));
        ds.addSeries("action_time",         actionTimes     .toArray(new Long[0]));
        ds.addSeries("action_type",         actionTypes     .toArray(new String[0]));
        ds.addSeries("selection_type",      selectionTypes  .toArray(new String[0]));
        ds.addSeries("target_key",          targetKeys      .toArray(new Long[0]));
        ds.addSeries("inference_time_ms",   inferenceDurations.toArray(new Long[0]));
        return ds;
    }

    @Override
    public String getName() {
        return id + "/trajectory_actions";
    }
}
