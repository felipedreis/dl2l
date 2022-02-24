package br.cefetmg.lsi.l2l.analysis.extractor;

import br.cefetmg.lsi.l2l.analysis.AnalysisUtil;
import br.cefetmg.lsi.l2l.analysis.dataset.DataSet;
import br.cefetmg.lsi.l2l.common.SequentialId;
import br.cefetmg.lsi.l2l.creature.bd.ActionSelectionType;
import br.cefetmg.lsi.l2l.creature.common.ActionType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.persistence.EntityManager;
import javax.persistence.Query;
import javax.persistence.TypedQuery;

/**
 * Created by felipe on 11/12/15.
 */
public class ChoicesOverTimeExtractor extends CreatureExtractor {

    public ChoicesOverTimeExtractor(EntityManager em, SequentialId id) {
        super(em, id);
    }

    @Override
    public DataSet extract() {

        int i, selectionsSize, categories;

        Query selectionsOverTimeQuery = em.createNamedQuery("ChosenActionState.getSelectionsOverTime");
        TypedQuery<Long> bornTimeQuery = em.createNamedQuery("CreatureState.getBornTime", Long.class);

        List actions = Arrays.asList(ActionType.EAT, ActionType.PLAY, ActionType.TOUCH);

        Long bornTime = bornTimeQuery
                .setParameter("keysuper", id.key)
                .getSingleResult();

        List<Object[]> selectionsOverTime = selectionsOverTimeQuery
                .setParameter("bornTime", bornTime)
                .setParameter("keysuper", id.key)
                .setParameter("actions", actions)
                .getResultList();

        Double [] timeSeries = new Double[selectionsOverTime.size()];
        List<ActionSelectionType> selections = new ArrayList<>();


        i = 0;

        for(Object[] selection : selectionsOverTime) {
            timeSeries[i] = ((Long) selection[0]).doubleValue();
            selections.add(((ActionSelectionType) selection[1]));

            i++;
        }

        String[] legends = AnalysisUtil.legends(ActionSelectionType.values());

        selectionsSize = selections.size();
        categories = legends.length;

        Integer[][] frequenciesSeries = AnalysisUtil.accumulatedFrequencies(selections, selectionsSize, categories);

        DataSet dataSet = new DataSet(selectionsSize);

        dataSet.addSeries("time", timeSeries);

        if(frequenciesSeries != null)
            for(i = 0; i < categories; ++i) {
                dataSet.addSeries(legends[i], frequenciesSeries[i]);
            }

        return dataSet;
    }

    @Override
    public String getName() {
        return id + "/choicesOverTime";
    }
}
