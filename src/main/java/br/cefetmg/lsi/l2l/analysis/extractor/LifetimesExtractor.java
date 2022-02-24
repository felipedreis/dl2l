package br.cefetmg.lsi.l2l.analysis.extractor;

import java.util.List;

import javax.persistence.EntityManager;
import javax.persistence.Query;
import javax.persistence.TypedQuery;

import br.cefetmg.lsi.l2l.analysis.dataset.DataSet;
import br.cefetmg.lsi.l2l.common.SequentialId;

/**
 * Created by felipe on 11/12/15.
 */
public class LifetimesExtractor extends SampleExtractor {

    public LifetimesExtractor(EntityManager em, List<SequentialId> creaturesIds) {
        super(em, creaturesIds);
    }

    @Override
    public DataSet extract() {
        Long [] idsSeries = new Long[ids.size()];
        Double [] lifetimeSeries = new Double[ids.size()];

        int i = 0;

        Query lifetimesQuery = em.createNamedQuery("CreatureState.getLifetimes");

        List<Object[]> lifetimesResult = lifetimesQuery.getResultList();

        for(Object[] result : lifetimesResult) {
            SequentialId id = ((SequentialId) result[0]);

            if(!ids.contains(id))
                continue;

            idsSeries[i] = id.key;
            lifetimeSeries[i] = ((Long) result[1]).doubleValue() * MILLIS_TO_SECONDS;
            i++;
        }

        DataSet dataSet = new DataSet(ids.size());
        dataSet.addSeries("ids", idsSeries);
        dataSet.addSeries("lifetime", lifetimeSeries);

        return dataSet;
    }

    @Override
    public String getName() {
        return "lifetimes";
    }
    
}
