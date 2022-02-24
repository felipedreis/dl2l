package br.cefetmg.lsi.l2l.analysis.extractor;

import java.math.BigDecimal;
import java.util.List;

import javax.persistence.EntityManager;
import javax.persistence.Query;
import javax.persistence.TypedQuery;

import br.cefetmg.lsi.l2l.analysis.AnalysisUtil;
import br.cefetmg.lsi.l2l.analysis.dataset.DataSet;
import br.cefetmg.lsi.l2l.common.SequentialId;

/**
 * Created by felipe on 28/01/16.
 */
public class BehaviouralEfficiencyExtractor extends CreatureExtractor {

    public BehaviouralEfficiencyExtractor(EntityManager em, SequentialId id) {
        super(em, id);
    }

    @Override
    public DataSet extract() {

        int i = 0;

        Query behaviouralEfficiencyQuery = em.createNamedQuery("BehaviouralEfficiencyState.getBehaviouralEfficiency");

        TypedQuery<Long> bornTimeQuery = em
                .createNamedQuery("CreatureState.getBornTime", Long.class);

        Long bornTime = bornTimeQuery
                .setParameter("keysuper", id.key)
                .getSingleResult();

        List<Object[]> simpleTaskResults = behaviouralEfficiencyQuery
                .setParameter(1, bornTime)
                .setParameter(2, MILLIS_TO_MINUTES)
                .setParameter(3, 3)
                .setParameter(4, id.key)
                .setParameter(5, false)
                .getResultList();

        List<Object[]>  complexTaskResults = behaviouralEfficiencyQuery
                .setParameter(1, bornTime)
                .setParameter(2, MILLIS_TO_MINUTES)
                .setParameter(3, 3)
                .setParameter(4, id.key)
                .setParameter(5, true)
                .getResultList();

        Double [] simpleTime = new Double[simpleTaskResults.size()];
        Double [] simpleEfficiency = new Double[simpleTaskResults.size()];

        for (Object[] result : simpleTaskResults) {
            simpleTime[i] = ((BigDecimal) result[0]).doubleValue();
            simpleEfficiency[i] = (Double) result[1];
            i++;
        }

        i = 0;

        Double [] complexTime = new Double[complexTaskResults.size()];
        Double [] complexEfficiency = new Double[complexTaskResults.size()];

        for (Object[] result : complexTaskResults) {
            complexTime[i] = ((BigDecimal) result[0]).doubleValue();
            complexEfficiency[i] = (Double) result[1];
            i++;
        }

        Double[][] merged = AnalysisUtil.mergeOnTime(simpleTime, simpleEfficiency, complexTime, complexEfficiency);

        DataSet dataSet = new DataSet(merged[0].length);
        dataSet.addSeries("time", merged[0]);
        dataSet.addSeries("simpleEfficiency", merged[1]);
        dataSet.addSeries("complexEfficiency", merged[2]);

        return dataSet;
    }

    @Override
    public String getName() {
        return id + "/behaviouralEfficiency";
    }
}
