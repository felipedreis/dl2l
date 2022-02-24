package br.cefetmg.lsi.l2l.analysis.extractor;

import java.util.List;
import java.util.stream.Collectors;

import javax.persistence.EntityManager;
import javax.persistence.TypedQuery;

import br.cefetmg.lsi.l2l.analysis.AnalysisUtil;
import br.cefetmg.lsi.l2l.analysis.dataset.DataSet;
import br.cefetmg.lsi.l2l.common.SequentialId;
import br.cefetmg.lsi.l2l.world.FruitType;
import br.cefetmg.lsi.l2l.world.WorldObjectType;

/**
 * Created by felipe on 28/01/16.
 */
public class AccumulatedNutrientChoicesExtractor extends CreatureExtractor {

    public AccumulatedNutrientChoicesExtractor(EntityManager em, SequentialId id) {
        super(em, id);
    }

    @Override
    public DataSet extract() {

        TypedQuery<String> nutrientInteractionsQuery = em
                .createNamedQuery(
                        "MouthInteractionState.getNutrientInteractions",
                        String.class);

        List<String> nutrientInteractions = nutrientInteractionsQuery
                .setParameter("keysuper", id.key)
                .getResultList();

        List<FruitType> fruitInteractions = nutrientInteractions.stream()
                .map((String str) -> FruitType.valueOf(str))
                .collect(Collectors.toList());

        int n = FruitType.values().length;

        String [] legends = AnalysisUtil.legends(FruitType.values());

        Integer[][] frequencies = AnalysisUtil.accumulatedFrequencies(
                fruitInteractions, nutrientInteractions.size(), n);

        DataSet result = new DataSet(nutrientInteractions.size());

        if(frequencies != null)
            for(int i = 0; i < n; ++i)
                result.addSeries(legends[i], frequencies[i]);

        return result;
    }

    @Override
    public String getName() {
        return id + "/accumulatedNutrientChoices";
    }
}
