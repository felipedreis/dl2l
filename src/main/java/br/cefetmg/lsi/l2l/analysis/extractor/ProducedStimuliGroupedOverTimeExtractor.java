package br.cefetmg.lsi.l2l.analysis.extractor;

import br.cefetmg.lsi.l2l.analysis.dataset.DataSet;
import br.cefetmg.lsi.l2l.common.SequentialId;
import br.cefetmg.lsi.l2l.stimuli.Stimuli;

import java.util.Arrays;
import java.util.List;

import javax.persistence.EntityManager;
import javax.persistence.Query;
import javax.persistence.TypedQuery;

/**
 * Created by felipe on 16/02/16.
 */
public class ProducedStimuliGroupedOverTimeExtractor extends CreatureExtractor {

    public ProducedStimuliGroupedOverTimeExtractor(EntityManager em, SequentialId id) {
        super(em, id);
    }

    @Override
    public DataSet extract() {
        final double TIME_CONSTANT = MILLIS_TO_MINUTES;

        TypedQuery<Long> bornTimeQuery = em.createNamedQuery("CreatureState.getBornTime", Long.class);
        TypedQuery<Long> deadTimeQuery = em.createNamedQuery("CreatureState.getDeadTime", Long.class);

        Long bornTime = bornTimeQuery
                .setParameter("keysuper", id.key)
                .getSingleResult();

        Long deadTime = deadTimeQuery
                .setParameter("keysuper", id.key)
                .getSingleResult();

        double lifetime = (deadTime - bornTime) * TIME_CONSTANT;


        int n = (int) Math.ceil(lifetime ) + 1;

        Double [] time = new Double[n];

        for(int i = 0; i < n; ++i)
            time[i] = (double) i;

        DataSet data = new DataSet(n);
        data.addSeries("time", time);

        for (String stimulusClass : Stimuli.internalStimuli) {

            Query producedStimuliQuery = em.createNamedQuery("StimulusState.getProducedStimulusGroupedByTime");

            List<Object[]> producedStimuli = producedStimuliQuery
                    .setParameter(1, bornTime)
                    .setParameter(2, MILLIS_TO_MINUTES)
                    .setParameter(3, id.key)
                    .setParameter(4, stimulusClass)
                    .getResultList();

            if (n > 0) {
                Long[] count = new Long[n];

                Arrays.fill(count, 0L);

                for (Object[] row : producedStimuli) {
                    double instant = ((Double) row[0]).doubleValue();
                    int index = (int) instant;

                    count[index] = (Long) row[1];
                }

                data.addSeries(stimulusClass, count);
            }
        }

        return data;
    }

    @Override
    public String getName() {
        return id + "/producedStimuliGroupedOverTime";
    }
}
