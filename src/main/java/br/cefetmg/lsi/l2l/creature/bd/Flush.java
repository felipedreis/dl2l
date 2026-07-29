package br.cefetmg.lsi.l2l.creature.bd;

import java.io.Serializable;

/**
 * Sent to {@link BDActor} to guarantee every write enqueued strictly before this message has
 * been committed. Relies on {@link br.cefetmg.lsi.l2l.common.ComponentMessageQueue}'s FIFO
 * batching: any {@link PersistenceState}s queued ahead of a Flush are always delivered (and
 * persisted) in an earlier {@code onReceive} call, never merged into the same batch as the
 * Flush itself - see docs/plans/bdactor-async-persistence-with-drain.md.
 */
public record Flush() implements Serializable {
}
