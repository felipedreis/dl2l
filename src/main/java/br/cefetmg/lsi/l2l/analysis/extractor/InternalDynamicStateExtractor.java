package br.cefetmg.lsi.l2l.analysis.extractor;

import br.cefetmg.lsi.l2l.analysis.dataset.DataSet;
import br.cefetmg.lsi.l2l.common.SequentialId;

import javax.persistence.EntityManager;
import java.util.ArrayList;
import java.util.List;

public class InternalDynamicStateExtractor extends CreatureExtractor {

    public InternalDynamicStateExtractor(EntityManager em, SequentialId id) {
        super(em, id);
    }

    @Override
    public DataSet extract() {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNamedQuery("InternalDynamicState.getForTrajectory")
                .setParameter(1, id.key)
                .getResultList();

        int n = rows.size();
        List<Long>   creatureKeys     = new ArrayList<>(n);
        List<Long>   regulationTimes  = new ArrayList<>(n);
        List<Double> finalHunger      = new ArrayList<>(n);
        List<Double> finalSleep       = new ArrayList<>(n);
        List<Double> finalApathy      = new ArrayList<>(n);
        List<Double> finalStress      = new ArrayList<>(n);
        List<Double> finalPain        = new ArrayList<>(n);
        List<Double> finalTedium      = new ArrayList<>(n);
        List<Double> finalFear        = new ArrayList<>(n);
        List<Double> finalCuriosity   = new ArrayList<>(n);
        List<Double> finalFertility   = new ArrayList<>(n);

        for (Object[] row : rows) {
            creatureKeys   .add(((Number) row[0]).longValue());
            regulationTimes.add(((Number) row[1]).longValue());
            finalHunger    .add(((Number) row[2]).doubleValue());
            finalSleep     .add(((Number) row[3]).doubleValue());
            finalApathy    .add(((Number) row[4]).doubleValue());
            finalStress    .add(((Number) row[5]).doubleValue());
            finalPain      .add(((Number) row[6]).doubleValue());
            finalTedium    .add(((Number) row[7]).doubleValue());
            finalFear      .add(((Number) row[8]).doubleValue());
            finalCuriosity .add(((Number) row[9]).doubleValue());
            finalFertility .add(((Number) row[10]).doubleValue());
        }

        DataSet ds = new DataSet(n);
        ds.addSeries("creatureKey",      creatureKeys   .toArray(new Long[0]));
        ds.addSeries("regulation_time",  regulationTimes.toArray(new Long[0]));
        ds.addSeries("final_hunger",     finalHunger    .toArray(new Double[0]));
        ds.addSeries("final_sleep",      finalSleep     .toArray(new Double[0]));
        ds.addSeries("final_apathy",     finalApathy    .toArray(new Double[0]));
        ds.addSeries("final_stress",     finalStress    .toArray(new Double[0]));
        ds.addSeries("final_pain",       finalPain      .toArray(new Double[0]));
        ds.addSeries("final_tedium",     finalTedium    .toArray(new Double[0]));
        ds.addSeries("final_fear",       finalFear      .toArray(new Double[0]));
        ds.addSeries("final_curiosity",  finalCuriosity .toArray(new Double[0]));
        ds.addSeries("final_fertility",  finalFertility .toArray(new Double[0]));
        return ds;
    }

    @Override
    public String getName() {
        return id + "/trajectory_emotions";
    }
}
