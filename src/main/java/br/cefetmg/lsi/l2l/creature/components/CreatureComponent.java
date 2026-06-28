package br.cefetmg.lsi.l2l.creature.components;

import br.cefetmg.lsi.l2l.common.SequentialId;
import br.cefetmg.lsi.l2l.creature.ComponentRef;
import br.cefetmg.lsi.l2l.creature.Creature;
import br.cefetmg.lsi.l2l.creature.bd.PersistenceState;
import br.cefetmg.lsi.l2l.creature.bd.Persister;

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

    private SequentialId nextStimulusId;

    private Persister persister;

    private ComponentRef selfRef;

    public CreatureComponent(SequentialId id) {
        this.id = id;
        this.nextStimulusId = new SequentialId(id.sequential);
        this.logger = Logger.getLogger(this.getClass().getName());
    }

    /**
     * Called by the runtime adapter ({@link ComponentActor} in production, the test
     * harness in tests) before any message is delivered. Subclasses may override
     * {@link #preStart()} for extra wiring.
     */
    public final void init(Creature creature, Persister persister, ComponentRef selfRef) {
        this.creature = creature;
        this.persister = persister;
        this.selfRef = selfRef;
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

    protected final void persist(PersistenceState... states) {
        if (persister == null) return;
        logger.fine(() -> "persisting " + states.length + " state(s)");
        persister.persist(states);
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
