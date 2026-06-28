package br.cefetmg.lsi.l2l.creature.testing;

import br.cefetmg.lsi.l2l.creature.ComponentRef;
import br.cefetmg.lsi.l2l.creature.components.CreatureComponent;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Single-threaded analogue of {@code ComponentMessageQueue}.
 *
 * Owns one {@link CreatureComponent}; queues messages sent through {@link #tell(Object)};
 * delivers them in a single batch to the component's {@link CreatureComponent#onReceive}
 * when {@link #drain()} is called. Anything the component sends back to its own
 * dispatcher during {@code onReceive} is held for the NEXT drain — same semantics as
 * production, where self-tells land in the next mailbox batch.
 *
 * Strings ({@code ""} tick wake-ups) are ignored by the queue but {@link #drain()} still
 * runs {@code onReceive} with whatever's buffered (possibly empty), which is what the
 * production {@code ComponentMessageQueue.dequeue()} does.
 */
public final class BatchingDispatcher implements ComponentRef {

    private static final Logger LOGGER = Logger.getLogger(BatchingDispatcher.class.getName());

    private final CreatureComponent component;
    private final Deque<Object> queue = new ArrayDeque<>();

    public BatchingDispatcher(CreatureComponent component) {
        this.component = component;
    }

    @Override
    public void tell(Object msg) {
        if (msg instanceof String) return; // tick wake-up — same as ComponentMessageQueue
        queue.addLast(msg);
    }

    public boolean hasPending() {
        return !queue.isEmpty();
    }

    /**
     * Snapshot the current queue and deliver as a single list to the component.
     * Anything sent into this dispatcher during {@code onReceive} stays in the queue
     * and is processed in a future drain.
     */
    public void drain() {
        List<Object> batch;
        if (queue.isEmpty()) {
            batch = new ArrayList<>();
        } else {
            batch = new ArrayList<>(queue);
            queue.clear();
        }
        // Mirror Akka's default supervisor strategy: a failing onReceive is logged and
        // the batch is dropped, processing continues. Without this, latent bugs in
        // production paths (e.g. OperantConditioning rejecting unknown targets) would
        // bubble up and crash the test harness.
        try {
            component.onReceive(batch);
        } catch (RuntimeException ex) {
            LOGGER.log(Level.WARNING,
                    component.getClass().getSimpleName() + ".onReceive failed on batch of size "
                            + batch.size() + ": " + ex,
                    ex);
        }
    }

    public CreatureComponent component() {
        return component;
    }
}
