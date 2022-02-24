package br.cefetmg.lsi.l2l.analysis.extractor;

import br.cefetmg.lsi.l2l.analysis.dataset.DataSet;
import br.cefetmg.lsi.l2l.common.SequentialId;

import javax.persistence.EntityManager;
import javax.persistence.Query;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Created by felipe on 22/10/16.
 */
public class TotalEnvironmentalStimuliChanged extends SampleExtractor {


    public TotalEnvironmentalStimuliChanged(EntityManager em, List<SequentialId> creatureIds) {
        super(em, creatureIds);
    }
    @Override
    public DataSet extract() {

        Long [] idsSeries = new Long[ids.size()];
        Long [] changedStimuli = new Long[ids.size()];

        int i = 0;

        Query countQuery = em.createNamedQuery("StimulusState.countEnvStimuli");

        List<Long> superKeys = ids.stream()
                .map(id -> id.key)
                .collect(Collectors.toList());

        List<Object[]> rows = countQuery
                .getResultList();

        System.out.println(countQuery.toString());

        for (Object [] row : rows) {
            Long id = (Long) row[0];

            if (superKeys.contains(id)) {
                idsSeries[i] = (Long) row[0];
                changedStimuli[i] = (Long) row[1];
                i++;
            }
        }

        DataSet dataSet = new DataSet(ids.size());
        dataSet.addSeries("ids", idsSeries);
        dataSet.addSeries("environmental_stimuli_count", changedStimuli);

        return dataSet;
    }

    @Override
    public String getName() {
        return "environmentalStimuliChange";
    }
}
