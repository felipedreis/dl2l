package br.cefetmg.lsi.l2l.analysis.extractor;

import br.cefetmg.lsi.l2l.analysis.dataset.DataSet;
import br.cefetmg.lsi.l2l.common.SequentialId;
import br.cefetmg.lsi.l2l.world.WorldObjectType;

import javax.persistence.EntityManager;
import javax.persistence.Query;
import java.io.ByteArrayInputStream;
import java.io.ObjectInputStream;
import java.util.ArrayList;
import java.util.List;

public class PerceptionCoverageExtractor extends SampleExtractor {

    public PerceptionCoverageExtractor(EntityManager em, List<SequentialId> ids) {
        super(em, ids);
    }

    @Override
    public DataSet extract() {
        List<Long>   creatureKeys = new ArrayList<>();
        List<String> objectTypes  = new ArrayList<>();
        List<Double> distances    = new ArrayList<>();
        List<Double> angles       = new ArrayList<>();
        List<Long>   times        = new ArrayList<>();

        Query q = em.createNamedQuery("ObjectSeenState.getPerceptionsByCreature");
        for (SequentialId id : ids) {
            q.setParameter(1, id.key);
            @SuppressWarnings("unchecked")
            List<Object[]> rows = q.getResultList();
            for (Object[] row : rows) {
                creatureKeys.add((Long)   row[0]);
                objectTypes .add(deserializeTypeName(row[1]));
                distances   .add((Double) row[2]);
                angles      .add((Double) row[3]);
                times       .add((Long)   row[4]);
            }
        }

        int n = creatureKeys.size();
        DataSet ds = new DataSet(n);
        ds.addSeries("creatureKey", creatureKeys.toArray(new Long[0]));
        ds.addSeries("objectType",  objectTypes .toArray(new String[0]));
        ds.addSeries("distance",    distances   .toArray(new Double[0]));
        ds.addSeries("angle",       angles      .toArray(new Double[0]));
        ds.addSeries("time",        times       .toArray(new Long[0]));
        return ds;
    }

    private static String deserializeTypeName(Object raw) {
        try {
            byte[] bytes = (byte[]) raw;
            try (ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(bytes))) {
                return ((WorldObjectType) ois.readObject()).name();
            }
        } catch (Exception e) {
            return String.valueOf(raw);
        }
    }

    @Override
    public String getName() {
        return "perceptionCoverage";
    }
}
