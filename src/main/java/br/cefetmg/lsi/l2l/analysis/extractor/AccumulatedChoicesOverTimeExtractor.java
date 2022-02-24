package br.cefetmg.lsi.l2l.analysis.extractor;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

import javax.persistence.EntityManager;
import javax.persistence.Query;
import javax.persistence.TypedQuery;

import br.cefetmg.lsi.l2l.analysis.dataset.DataSet;
import br.cefetmg.lsi.l2l.common.SequentialId;
import br.cefetmg.lsi.l2l.creature.bd.ActionSelectionType;

/**
 * Created by felipe on 18/02/16.
 */
public class AccumulatedChoicesOverTimeExtractor extends CreatureExtractor {

    public AccumulatedChoicesOverTimeExtractor(EntityManager em, SequentialId id) {
        super(em, id);
    }

    @Override
    public DataSet extract() {
        TypedQuery<Long> bornTimeQuery = em.createNamedQuery("CreatureState.getBornTime", Long.class);
        TypedQuery<Long> deadTimeQuery = em.createNamedQuery("CreatureState.getDeadTime", Long.class);
        Query selectionsGroupedOverTimeQuery = em.createNamedQuery("ChosenActionState.getSelectionsGroupedOverTime");

        Long bornTime = bornTimeQuery
                .setParameter("keysuper", id.key)
                .getSingleResult();
        Long deadTime = deadTimeQuery
                .setParameter("keysuper", id.key)
                .getSingleResult();

        double lifetime = (deadTime - bornTime) * MILLIS_TO_MINUTES;
        int size = (int) Math.ceil(lifetime/0.01) + 1;
        Double[] time = new Double[size];

        for (int i = 0; i < size; ++i) {
            time[i] = i/100d;
        }

        DataSet dataSet = new DataSet(size);

        if (size == 0) {
            return dataSet;
        }

        dataSet.addSeries("time", time);

        for (ActionSelectionType type : ActionSelectionType.values()) {
            List<Object[]> selectionsGroupedOverTime = (List<Object[]>) selectionsGroupedOverTimeQuery
                    .setParameter(1, bornTime) // initial time
                    .setParameter(2, MILLIS_TO_MINUTES) // time constant
                    .setParameter(3, 5) // time precision
                    .setParameter(4, id.key) // id of creature
                    .setParameter(5, type.name()) // type of filter
                    .getResultList();

            Long[] accumulated = new Long[size];

            Arrays.fill(accumulated, 0L);
            int i;
            for (Object[] selections : selectionsGroupedOverTime) {
                double t = ((BigDecimal) selections[0]).doubleValue();

                i = (int) Math.round(t * 100);

                accumulated[i] = (Long) selections[1];
            }

            for (i = 1; i < accumulated.length; ++i) {
                accumulated[i] = accumulated[i] + accumulated[i - 1];
            }

            dataSet.addSeries(type.name(), accumulated);
        }

        return dataSet;
    }

    @Override
    public String getName() {
        return id + "/accumulatedChoicesOverTime";
    }
}
