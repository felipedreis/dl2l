package br.cefetmg.lsi.l2l.analysis.extractor;

import java.math.BigDecimal;
import java.util.List;

import javax.persistence.EntityManager;
import javax.persistence.Query;
import javax.persistence.TypedQuery;

import br.cefetmg.lsi.l2l.analysis.dataset.DataSet;
import br.cefetmg.lsi.l2l.common.SequentialId;
import br.cefetmg.lsi.l2l.creature.bd.EmotionalState;

/**
 * Created by felipe on 16/02/16.
 */
public class ArousalHistoryExtractor extends CreatureExtractor {

    private static final double timeConstant = MILLIS_TO_MINUTES;
    private static final int timePrecision = 3;

    public ArousalHistoryExtractor(EntityManager em, SequentialId id){
        super(em, id);
    }

    @Override
    public DataSet extract() {

        Query arousalOverTimeQuery = em.createNamedQuery("InternalDynamicState.getArousalOverTime");

        TypedQuery<Long> bornTimeQuery = em
                .createNamedQuery("CreatureState.getBornTime", Long.class);

        Long bornTime = bornTimeQuery
                .setParameter("keysuper", id.key)
                .getSingleResult();

        List<Object[]> arousalOverTime = arousalOverTimeQuery
                .setParameter(1, bornTime)
                .setParameter(2, timeConstant)
                .setParameter(3, timePrecision)
                .setParameter(4, id.key)
                .getResultList();

        int size = arousalOverTime.size();

        DataSet data = new DataSet(size);
        data.addSeries("time", new Double[size]);
        data.addSeries("hunger", new Double[size]);
        data.addSeries("sleep", new Double[size]);

        int i = 0;

        for(Object[]  o : arousalOverTime) {
           data.setValue("time", i, ((BigDecimal)o[0]).doubleValue());
            data.setValue("hunger", i,  o[1]);
            data.setValue("sleep", i, o[2]);
            i++;
        }

        return data;
    }

    @Override
    public String getName() {
        return id + "/arousalHistory";
    }
}
