package br.cefetmg.lsi.l2l.analysis.extractor;

import br.cefetmg.lsi.l2l.analysis.dataset.DataSet;
import br.cefetmg.lsi.l2l.common.SequentialId;

import javax.persistence.EntityManager;
import java.util.ArrayList;
import java.util.List;

public class EngramStateExtractor extends CreatureExtractor {

    public EngramStateExtractor(EntityManager em, SequentialId id) {
        super(em, id);
    }

    @Override
    public DataSet extract() {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNamedQuery("EngramState.getForCreature")
                .setParameter(1, id.key)
                .getResultList();

        int n = rows.size();
        List<Long>   creatureKeys     = new ArrayList<>(n);
        List<String> actionTypes      = new ArrayList<>(n);
        List<Long>   layCycles        = new ArrayList<>(n);
        List<Long>   reinforcedCycles = new ArrayList<>(n);
        List<Long>   cycleGaps        = new ArrayList<>(n);
        List<Double> eligibilities    = new ArrayList<>(n);
        List<Double> emotionDeltas    = new ArrayList<>(n);

        for (Object[] row : rows) {
            creatureKeys    .add(((Number) row[0]).longValue());
            actionTypes     .add(String.valueOf(row[1]));
            layCycles       .add(((Number) row[2]).longValue());
            reinforcedCycles.add(((Number) row[3]).longValue());
            cycleGaps       .add(((Number) row[4]).longValue());
            eligibilities   .add(((Number) row[5]).doubleValue());
            emotionDeltas   .add(((Number) row[6]).doubleValue());
        }

        DataSet ds = new DataSet(n);
        ds.addSeries("creature_key",     creatureKeys    .toArray(new Long[0]));
        ds.addSeries("action_type",      actionTypes     .toArray(new String[0]));
        ds.addSeries("lay_cycle",        layCycles       .toArray(new Long[0]));
        ds.addSeries("reinforced_cycle", reinforcedCycles.toArray(new Long[0]));
        ds.addSeries("cycle_gap",        cycleGaps       .toArray(new Long[0]));
        ds.addSeries("eligibility",      eligibilities   .toArray(new Double[0]));
        ds.addSeries("emotion_delta",    emotionDeltas   .toArray(new Double[0]));
        return ds;
    }

    @Override
    public String getName() {
        return id + "/engrams";
    }
}
