package br.cefetmg.lsi.l2l.analysis.extractor;

import java.util.ArrayList;
import java.util.List;

import javax.persistence.EntityManager;
import javax.persistence.TypedQuery;

import br.cefetmg.lsi.l2l.analysis.dataset.DataSet;
import br.cefetmg.lsi.l2l.common.SequentialId;

/**
 * Created by felipe on 11/12/15.
 */
public class TracingExtractor extends SampleExtractor {

    public TracingExtractor(EntityManager em, List<SequentialId> creaturesIds) {
        super(em, creaturesIds);
    }

    @Override
    public DataSet extract() {
    	ArrayList<Long> idsSeries = new ArrayList<>();
        ArrayList<Double> initialXSeries  = new ArrayList<>();
        ArrayList<Double> finalXSeries  = new ArrayList<>();
        ArrayList<Double> initialYSeries  = new ArrayList<>();
        ArrayList<Double> finalYSeries  = new ArrayList<>();

        TypedQuery<Object[]> traceQuery = em.createNamedQuery("BodyState.getCreatureTrace", Object[].class);

        for (SequentialId id : ids) {
            traceQuery.setParameter("keySuper", id.key);
            List<Object[]> creatureCoords = traceQuery.getResultList();
            
            for (Object[] coords : creatureCoords) {
                idsSeries.add(id.key);
                initialXSeries.add((double) coords[0]);
                initialYSeries.add((double) coords[1]);
                finalXSeries.add((double) coords[2]);
                finalYSeries.add((double) coords[3]);
            }
            
        }

        int nRows = idsSeries.size();
        DataSet dataSet = new DataSet(nRows);
        dataSet.addSeries("ids", idsSeries.toArray(new Long[nRows]));
        dataSet.addSeries("initialXSeries", initialXSeries.toArray(new Double[nRows]));
        dataSet.addSeries("initialYSeries", initialYSeries.toArray(new Double[nRows]));
        dataSet.addSeries("finalXSeries", finalXSeries.toArray(new Double[nRows]));
        dataSet.addSeries("finalYSeries", finalYSeries.toArray(new Double[nRows]));

        return dataSet;
    }

    @Override
    public String getName() {
        return "tracing";
    }
    
}
