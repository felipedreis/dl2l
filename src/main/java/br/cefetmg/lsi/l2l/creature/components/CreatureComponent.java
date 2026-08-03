package br.cefetmg.lsi.l2l.creature.components;

import br.cefetmg.lsi.l2l.common.SequentialId;
import br.cefetmg.lsi.l2l.creature.ComponentRef;
import br.cefetmg.lsi.l2l.creature.Creature;
import br.cefetmg.lsi.l2l.creature.bd.PersistenceState;
import br.cefetmg.lsi.l2l.metrics.MetricsExtension;

import java.util.ArrayList;
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

    /**
     * Resolved once in {@link #init}, not re-resolved per {@link #persist} call. {@code
     * creature.bd()} is a blocking Akka TypedActor accessor round-trip (every non-void
     * {@link Creature} method is); its underlying value never changes for the creature's
     * lifetime, so caching it here removes ~14 blocking round-trips/cognitive-cycle from
     * the hot path (see docs/plans/issue-77-bdactor-oom-fix.md).
     */
    private ComponentRef bdRef;

    protected final Logger logger;

    /** Per-JVM Micrometer registry; null in test harnesses that don't wire one. */
    protected MetricsExtension.Impl metricsExt;

    private SequentialId nextStimulusId;

    private ComponentRef selfRef;

    /**
     * Threshold (in flattened {@link PersistenceState}s, not {@link #persist} calls) at which
     * {@link #persist} auto-flushes its buffer as one consolidated array instead of telling
     * BDActor immediately. {@code DL2L_PERSIST_BUFFER_STATES=0} restores the old one-tell-per-
     * {@code persist()}-call behavior (passthrough). A cognitive cycle emits ~20-40 states
     * across ~14 {@code persist()} calls at up to ~300Hz - buffering to 256 cuts BDActor's
     * mailbox envelope rate by roughly two orders of magnitude. See
     * docs/plans/arrow-ipc-write-path.md, W4.
     *
     * <p>Resolved once per instance (not a shared static constant) so
     * {@link #setPersistBufferThresholdForTesting} can override just one component under
     * test without every other component/test in the same JVM sharing that override.
     */
    private int persistBufferThreshold = persistBufferStatesFromEnv();

    private static int persistBufferStatesFromEnv() {
        String v = System.getenv("DL2L_PERSIST_BUFFER_STATES");
        return v != null ? Integer.parseInt(v) : 256;
    }

    /**
     * {@code null} when {@link #persistBufferThreshold} is 0 (passthrough - see {@link #persist}).
     * Plain {@link ArrayList}, no locking: components are single-threaded actors (one message
     * at a time via {@code ComponentActor}/{@code ComponentMailbox}), so this is only ever
     * touched from that one thread.
     */
    private List<PersistenceState> persistBuffer =
            persistBufferThreshold > 0 ? new ArrayList<>(persistBufferThreshold) : null;

    public CreatureComponent(SequentialId id) {
        this.id = id;
        this.nextStimulusId = new SequentialId(id.sequential);
        this.logger = Logger.getLogger(this.getClass().getName());
    }

    /**
     * Test-only hook overriding this component's {@link #persistBufferThreshold} without
     * depending on process env vars (which, being read once per instance from
     * {@link System#getenv}, can't otherwise be varied within one test JVM). Production code
     * never calls this - the env var ({@code DL2L_PERSIST_BUFFER_STATES}) is the only knob
     * there. Public because test harnesses live in a different package
     * ({@code br.cefetmg.lsi.l2l.creature.testing}); any pending buffered states are flushed
     * first so switching modes mid-test never silently drops them.
     */
    public final void setPersistBufferThresholdForTesting(int threshold) {
        flushPersistBuffer();
        this.persistBufferThreshold = threshold;
        this.persistBuffer = threshold > 0 ? new ArrayList<>(threshold) : null;
    }

    /** Overload for callers (tests) that don't have a MetricsExtension to wire in. */
    public final void init(Creature creature, ComponentRef selfRef) {
        init(creature, selfRef, null);
    }

    /**
     * Called by the runtime adapter ({@link ComponentActor} in production, the test
     * harness in tests) before any message is delivered. Subclasses may override
     * {@link #preStart()} for extra wiring.
     */
    public final void init(Creature creature, ComponentRef selfRef, MetricsExtension.Impl metricsExt) {
        this.creature = creature;
        this.bdRef = creature.bd();
        this.selfRef = selfRef;
        this.metricsExt = metricsExt;
        try {
            preStart();
        } catch (Exception e) {
            throw new RuntimeException("preStart failed for " + getClass().getSimpleName(), e);
        }
    }

    /** Override to run extra setup once {@link #creature} is wired. */
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
     *
     * <p>Producer-side buffered when {@link #persistBufferThreshold} &gt; 0 (the default -
     * see docs/plans/arrow-ipc-write-path.md, W4): appends to {@link #persistBuffer} instead of
     * telling BDActor immediately, auto-flushing (still as one array, preserving the atomicity
     * guarantee above) once the buffer reaches the threshold. {@link #flushPersistBuffer()}
     * (called on threshold here, and from {@code ComponentActor.postStop()}) is what actually
     * sends.
     */
    protected final void persist(PersistenceState... states) {
        if (bdRef == null) return;
        if (persistBuffer == null) {
            logger.fine(() -> "persisting " + states.length + " state(s)");
            bdRef.tell(states);
            return;
        }
        for (PersistenceState s : states) persistBuffer.add(s);
        if (persistBuffer.size() >= persistBufferThreshold) {
            flushPersistBuffer();
        }
    }

    /**
     * Sends whatever is currently buffered as one consolidated array and clears the buffer -
     * a no-op if buffering is off ({@link #persistBufferThreshold}=0) or nothing is pending.
     * Called automatically once {@link #persist} fills the buffer to threshold, and once more
     * from {@code ComponentActor.postStop()} so a component dying mid-buffer (creature death,
     * actor restart) doesn't silently drop its last, sub-threshold batch of states - the only
     * residual window is between a persist() call and this component's own postStop(), strictly
     * smaller than the risk it replaces (every unbuffered persist() was already only as durable
     * as BDActor's mailbox FIFO, same as before this change).
     */
    final void flushPersistBuffer() {
        if (persistBuffer == null || persistBuffer.isEmpty() || bdRef == null) return;
        PersistenceState[] batch = persistBuffer.toArray(new PersistenceState[0]);
        persistBuffer.clear();
        logger.fine(() -> "flushing persist buffer of " + batch.length + " state(s)");
        bdRef.tell(batch);
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
