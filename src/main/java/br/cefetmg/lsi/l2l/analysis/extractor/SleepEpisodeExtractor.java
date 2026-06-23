package br.cefetmg.lsi.l2l.analysis.extractor;

import br.cefetmg.lsi.l2l.analysis.dataset.DataSet;
import br.cefetmg.lsi.l2l.common.SequentialId;

import javax.persistence.EntityManager;
import java.util.ArrayList;
import java.util.List;

/**
 * Exports one row per sleep episode, joined with consolidation episode statistics.
 * Produces columns: creature_key, onset_cycle, wake_cycle, duration_ticks,
 * engram_count, mean_eligibility, std_eligibility, batches_completed, aborted.
 */
public class SleepEpisodeExtractor extends CreatureExtractor {

    public SleepEpisodeExtractor(EntityManager em, SequentialId id) {
        super(em, id);
    }

    @Override
    public DataSet extract() {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(
                "SELECT ses.creature_key, ses.onset_cycle, ses.wake_cycle, ses.duration_ticks, " +
                "       ces.engram_count, ces.mean_eligibility, ces.std_eligibility, " +
                "       ces.batches_completed, ces.aborted " +
                "FROM data.sleep_episode_state ses " +
                "LEFT JOIN data.consolidation_episode_stat ces " +
                "       ON ses.creature_key = ces.creature_key AND ses.onset_cycle = ces.onset_cycle " +
                "WHERE ses.creature_key = ? " +
                "ORDER BY ses.onset_cycle")
                .setParameter(1, id.key)
                .getResultList();

        int n = rows.size();
        List<Long>    creatureKeys   = new ArrayList<>(n);
        List<Long>    onsetCycles    = new ArrayList<>(n);
        List<Long>    wakeCycles     = new ArrayList<>(n);
        List<Integer> durationTicks  = new ArrayList<>(n);
        List<Integer> engramCounts   = new ArrayList<>(n);
        List<Double>  meanElig       = new ArrayList<>(n);
        List<Double>  stdElig        = new ArrayList<>(n);
        List<Integer> batchesCompleted = new ArrayList<>(n);
        List<Boolean> aborted        = new ArrayList<>(n);

        for (Object[] row : rows) {
            creatureKeys   .add(((Number) row[0]).longValue());
            onsetCycles    .add(((Number) row[1]).longValue());
            wakeCycles     .add(((Number) row[2]).longValue());
            durationTicks  .add(((Number) row[3]).intValue());
            engramCounts   .add(row[4] != null ? ((Number) row[4]).intValue()    : 0);
            meanElig       .add(row[5] != null ? ((Number) row[5]).doubleValue() : 0.0);
            stdElig        .add(row[6] != null ? ((Number) row[6]).doubleValue() : 0.0);
            batchesCompleted.add(row[7] != null ? ((Number) row[7]).intValue()   : 0);
            aborted        .add(row[8] != null && (Boolean) row[8]);
        }

        DataSet ds = new DataSet(n);
        ds.addSeries("creature_key",      creatureKeys    .toArray(new Long[0]));
        ds.addSeries("onset_cycle",       onsetCycles     .toArray(new Long[0]));
        ds.addSeries("wake_cycle",        wakeCycles      .toArray(new Long[0]));
        ds.addSeries("duration_ticks",    durationTicks   .toArray(new Integer[0]));
        ds.addSeries("engram_count",      engramCounts    .toArray(new Integer[0]));
        ds.addSeries("mean_eligibility",  meanElig        .toArray(new Double[0]));
        ds.addSeries("std_eligibility",   stdElig         .toArray(new Double[0]));
        ds.addSeries("batches_completed", batchesCompleted.toArray(new Integer[0]));
        ds.addSeries("aborted",           aborted         .toArray(new Boolean[0]));
        return ds;
    }

    @Override
    public String getName() {
        return id + "/sleep_episodes";
    }
}
