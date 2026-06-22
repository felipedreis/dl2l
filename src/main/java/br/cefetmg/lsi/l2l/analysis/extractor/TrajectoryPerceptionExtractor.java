package br.cefetmg.lsi.l2l.analysis.extractor;

import br.cefetmg.lsi.l2l.analysis.dataset.DataSet;
import br.cefetmg.lsi.l2l.common.SequentialId;
import br.cefetmg.lsi.l2l.world.WorldObjectType;

import javax.persistence.EntityManager;
import java.io.ByteArrayInputStream;
import java.io.ObjectInputStream;
import java.util.ArrayList;
import java.util.List;

public class TrajectoryPerceptionExtractor extends CreatureExtractor {

    public TrajectoryPerceptionExtractor(EntityManager em, SequentialId id) {
        super(em, id);
    }

    @Override
    public DataSet extract() {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNamedQuery("ObjectSeenState.getForTrajectory")
                .setParameter(1, id.key)
                .getResultList();

        int n = rows.size();
        List<Long>   creatureKeys = new ArrayList<>(n);
        List<Long>   times        = new ArrayList<>(n);
        List<Long>   objectKeys   = new ArrayList<>(n);
        List<String> objectTypes  = new ArrayList<>(n);
        List<Double> distances    = new ArrayList<>(n);
        List<Double> angles       = new ArrayList<>(n);
        List<Double> directions   = new ArrayList<>(n);

        for (Object[] row : rows) {
            creatureKeys.add(((Number) row[0]).longValue());
            times       .add(((Number) row[1]).longValue());
            objectKeys  .add(((Number) row[2]).longValue());
            objectTypes .add(deserializeTypeName(row[3]));
            distances   .add(((Number) row[4]).doubleValue());
            angles      .add(((Number) row[5]).doubleValue());
            directions  .add(((Number) row[6]).doubleValue());
        }

        DataSet ds = new DataSet(n);
        ds.addSeries("creatureKey", creatureKeys.toArray(new Long[0]));
        ds.addSeries("time",        times       .toArray(new Long[0]));
        ds.addSeries("object_key",  objectKeys  .toArray(new Long[0]));
        ds.addSeries("object_type", objectTypes .toArray(new String[0]));
        ds.addSeries("distance",    distances   .toArray(new Double[0]));
        ds.addSeries("angle",       angles      .toArray(new Double[0]));
        ds.addSeries("direction",   directions  .toArray(new Double[0]));
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
        return id + "/trajectory_perceptions";
    }
}
