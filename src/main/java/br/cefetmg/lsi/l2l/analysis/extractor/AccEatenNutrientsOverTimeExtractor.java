package br.cefetmg.lsi.l2l.analysis.extractor;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

import javax.persistence.EntityManager;
import javax.persistence.Query;
import javax.persistence.TypedQuery;

import br.cefetmg.lsi.l2l.analysis.AnalysisUtil;
import br.cefetmg.lsi.l2l.analysis.dataset.DataSet;
import br.cefetmg.lsi.l2l.common.SequentialId;
import br.cefetmg.lsi.l2l.world.FruitType;
import br.cefetmg.lsi.l2l.world.WorldObjectType;

/**
 * Created by felipe on 28/01/16.
 */
public class AccEatenNutrientsOverTimeExtractor extends CreatureExtractor {
    private static final int TIME_PRECISION = 1;

    public AccEatenNutrientsOverTimeExtractor(EntityManager em, SequentialId id) {
        super(em, id);
    }

    @Override
    public DataSet extract() {

        Query groupedInteractionsQuery = em
                .createNamedQuery("MouthInteractionState.getEatenNutrientsGroupedOverTime");

        TypedQuery<Long> bornTimeQuery = em
                .createNamedQuery("CreatureState.getBornTime", Long.class);
        TypedQuery<Long> deadTimeQuery= em
                .createNamedQuery("CreatureState.getDeadTime", Long.class);

        WorldObjectType objectsType[] = FruitType.values();

        Long bornTime = bornTimeQuery
                .setParameter("keysuper", id.key)
                .getSingleResult();

        Long deadTime = deadTimeQuery
                .setParameter("keysuper", id.key)
                .getSingleResult();

        double lifetime = (deadTime - bornTime) * MILLIS_TO_MINUTES;
        double timeConstant = Math.pow(10, TIME_PRECISION);
        int intervals = (int) Math.ceil(lifetime * timeConstant) + 1;

        Double [] time = new Double[intervals];

        for (int i = 0; i < intervals; ++i) {
            time[i] = i / timeConstant;
        }

        DataSet results = new DataSet(intervals);
        results.addSeries("time", time);

        for(WorldObjectType type : objectsType) {

            List<Object[]> resultList = groupedInteractionsQuery
                    .setParameter(1, bornTime)
                    .setParameter(2, MILLIS_TO_MINUTES)
                    .setParameter(3, TIME_PRECISION)
                    .setParameter(4, type.name())
                    .setParameter(5, id.key)
                    .getResultList();

            Integer accumulatedEatenNutrients [] = new Integer[intervals];
            Arrays.fill(accumulatedEatenNutrients, 0);


            for(Object[] r : resultList) {

                double t = ((BigDecimal) r[0]).doubleValue();

                int index = (int) Math.round(t * timeConstant);
                int eatenNutrients = ((Long) r[1]).intValue();

                accumulatedEatenNutrients[index] = eatenNutrients;
            }

            for (int i = 1; i < accumulatedEatenNutrients.length; ++i)
                accumulatedEatenNutrients[i] += accumulatedEatenNutrients[i - 1];

            results.addSeries(type.name(), accumulatedEatenNutrients);
        }

        return results;
    }

    @Override
    public String getName() {
        return id + "/eatenNutrientsOverTime";
    }
}
