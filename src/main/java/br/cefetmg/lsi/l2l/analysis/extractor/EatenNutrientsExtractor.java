package br.cefetmg.lsi.l2l.analysis.extractor;

import java.util.List;

import javax.persistence.EntityManager;
import javax.persistence.TypedQuery;

import br.cefetmg.lsi.l2l.analysis.dataset.DataSet;
import br.cefetmg.lsi.l2l.common.SequentialId;

/**
 * Created by felipe on 27/01/16.
 */
public class EatenNutrientsExtractor extends SampleExtractor {

    public EatenNutrientsExtractor(EntityManager em, List<SequentialId> creaturesIds) {
        super(em, creaturesIds);
    }

    @Override
    public DataSet extract() {
        Long [] idSeries = new Long[ids.size()];
        Double [] eatenNutrientsSeries = new Double[ids.size()];

        int i = 0;

        for (SequentialId id : ids) {
            TypedQuery<Long> eatenNutrientsQuery = em.createNamedQuery(
                    "MouthInteractionState.getNumberOfEatenNutrients",
                    Long.class);

            Double eaten = eatenNutrientsQuery.setParameter(
                    "keysuper", id.key).getSingleResult().doubleValue();

            idSeries[i] = id.key;
            eatenNutrientsSeries[i] = eaten;
            i++;
        }

        DataSet dataSet = new DataSet(ids.size());
        dataSet.addSeries("ids", idSeries);
        dataSet.addSeries("eatenNutrients", eatenNutrientsSeries);

        return dataSet;
    }

    @Override
    public String getName() {
        return "eatenNutrients";
    }
    
}
