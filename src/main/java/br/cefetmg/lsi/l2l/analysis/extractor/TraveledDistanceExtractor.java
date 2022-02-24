package br.cefetmg.lsi.l2l.analysis.extractor;

import java.util.List;

import javax.persistence.EntityManager;

import br.cefetmg.lsi.l2l.analysis.dataset.DataSet;
import br.cefetmg.lsi.l2l.common.SequentialId;

/**
 * Created by felipe on 27/01/16.
 */
public class TraveledDistanceExtractor extends SampleExtractor {
	
    public TraveledDistanceExtractor(EntityManager em, List<SequentialId> creaturesIds) {
        super(em, creaturesIds);
    }

    @Override
    public DataSet extract() {
        List<Object[]> results = em.createNamedQuery("BodyState.getAllTraveledDistance").getResultList();

        Long [] idSeries = new Long[results.size()];
        Double [] distanceSeries = new Double[results.size()];
        int i = 0;

        for (Object[] result : results) {

            Long id = (Long) result[0];
            Double distance = (Double) result[1];

            SequentialId key = new SequentialId(id);

            if (ids.contains(key)) {

                idSeries[i] = id;
                distanceSeries[i] = distance;

                i++;
            }
            
        }

        DataSet dataSet = new DataSet(results.size());

        dataSet.addSeries("ids", idSeries);
        dataSet.addSeries("distances", distanceSeries);

        return dataSet;
    }

    @Override
    public String getName() {
        return "distances";
    }
    
}
