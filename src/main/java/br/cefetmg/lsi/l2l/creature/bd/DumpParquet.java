package br.cefetmg.lsi.l2l.creature.bd;

import java.io.Serializable;

/**
 * Sent to {@link BDActor} to dump every raw table to its own Parquet file under
 * {@code saveDir}/raw - see docs/plans/parquet-write-path.md. Always sent strictly after a
 * {@link Flush} completes (two sequential {@code Sync.ask} calls, see
 * {@code Holder.handleFinish()}), so every write is guaranteed durably committed by the time
 * this runs.
 */
public record DumpParquet() implements Serializable {
}
