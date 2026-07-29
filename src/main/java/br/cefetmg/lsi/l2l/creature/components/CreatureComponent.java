package br.cefetmg.lsi.l2l.creature.components;

import br.cefetmg.lsi.l2l.common.SequentialId;
import br.cefetmg.lsi.l2l.creature.ComponentRef;
import br.cefetmg.lsi.l2l.creature.Creature;
import br.cefetmg.lsi.l2l.creature.bd.PersistenceState;
import br.cefetmg.lsi.l2l.creature.bd.Persister;
import br.cefetmg.lsi.l2l.metrics.MetricsExtension;

import java.util.List;
import java.util.logging.Logger;

/**
 * Base class for all creature components. Used to extend {@code akka.actor.UntypedActor}
 * directly; that coupling has been moved out to {@link ComponentActor}. Subclasses
 * implement {@link #onReceive(List)} which is invoked with a list of stimuli batched
 * by the dispatcher (either {@code ComponentMailbox} in production or
 * {@code BatchingDispatcher} in tests).
 *
 * Constructed via reflection / {@code Props}, then wired via {@link #init} before any
 * message is delivered.
 */
public abstract class CreatureComponent {

    protected SequentialId id;

    protected Creature creature;

    protected final Logger logger;

    /** Per-JVM Micrometer registry; null in test harnesses that don't wire one. */
    protected MetricsExtension.Impl metricsExt;

    private SequentialId nextStimulusId;

    private Persister persister;

    private ComponentRef selfRef;

    public CreatureComponent(SequentialId id) {
        this.id = id;
        this.nextStimulusId = new SequentialId(id.sequential);
        this.logger = Logger.getLogger(this.getClass().getName());
    }

    /** Overload for callers (tests) that don't have a MetricsExtension to wire in. */
    public final void init(Creature creature, Persister persister, ComponentRef selfRef) {
        init(creature, persister, selfRef, null);
    }

    /**
     * Called by the runtime adapter ({@link ComponentActor} in production, the test
     * harness in tests) before any message is delivered. Subclasses may override
     * {@link #preStart()} for extra wiring.
     */
    public final void init(Creature creature, Persister persister, ComponentRef selfRef,
                            MetricsExtension.Impl metricsExt) {
        this.creature = creature;
        this.persister = persister;
        this.selfRef = selfRef;
        this.metricsExt = metricsExt;
        try {
            preStart();
        } catch (Exception e) {
            throw new RuntimeException("preStart failed for " + getClass().getSimpleName(), e);
        }
    }

    /** Override to run extra setup once {@link #creature} and {@link #persister} are wired. */
    public void preStart() throws Exception {
        // default: no-op
    }

    /** Override to release resources when the component is torn down. */
    public void postStop() throws Exception {
        // default: no-op
    }

    /** Subclasses implement the batch handler. */
    public abstract void onReceive(Object message);

    /**
     * Fire-and-forget: tells {@link Creature#bd()} (the per-JVM {@code BDActor}) instead of
     * blocking this component's own dispatcher thread on a synchronous JPA transaction.
     * {@code ComponentMessageQueue}'s FIFO batching coalesces everything queued between
     * BDActor's mailbox polls into one committed transaction - see
     * docs/plans/bdactor-async-persistence-with-drain.md.
     *
     * CONFIRMED LIVE: sends the whole {@code states} array as ONE message, not one .tell()
     * per state. Some states reference each other (e.g. an EyeState/ObjectSeenState's
     * @OneToOne changeStimulusState) - if the two ends of that reference landed in different
     * BDActor batches/transactions (possible if sent as separate messages and BDActor's
     * dispatcher thread happened to poll between them), the second transaction re-inserted
     * the already-committed, now-detached referenced entity and hit a duplicate-primary-key
     * constraint violation, which crashed BDActor outright (uncaught exception -> this
     * project's StoppingSupervisorStrategy stops rather than restarts). Sending one array
     * message keeps every persist(...) call atomic - always landed in the same batch/transaction,
     * never split - restoring the same one-call-one-transaction invariant the old synchronous
     * design always had.
     */
    protected final void persist(PersistenceState... states) {
        if (creature == null) return;
        logger.fine(() -> "persisting " + states.length + " state(s)");
        creature.bd().tell(states);
    }

    /**
     * A {@link ComponentRef} that points back at this component. Used by components
     * that need to enqueue messages onto themselves (e.g. {@code HomeostaticRegulation}
     * scheduling an analgesic response).
     */
    protected final ComponentRef self() {
        return selfRef;
    }

    public final SequentialId id() {
        return id;
    }

    protected final SequentialId nextStimulusId() {
        SequentialId id = nextStimulusId;
        nextStimulusId = nextStimulusId.next();
        return id;
    }
}
