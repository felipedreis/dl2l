package br.cefetmg.lsi.l2l.common;

import akka.actor.ActorRef;
import akka.actor.PoisonPill;
import akka.dispatch.Envelope;
import akka.dispatch.MessageQueue;
import br.cefetmg.lsi.l2l.creature.bd.PersistenceState;
import br.cefetmg.lsi.l2l.stimuli.Stimulus;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.Collectors;

/**
 * Created by felipe on 03/01/17.
 */
public class ComponentMessageQueue implements MessageQueue {

    private final Queue<Envelope> queue = new ConcurrentLinkedQueue<Envelope>();

    /**
     * Cap on how many top-level envelopes (a lone {@code Stimulus}/{@code PersistenceState}, or
     * one whole {@code PersistenceState[]} array) {@link #dequeue()} merges into a single batch.
     * {@code Integer.MAX_VALUE} (the no-arg constructor's default) preserves the original
     * unbounded-drain behavior. A finite cap bounds how large a single {@code BDActor} transaction
     * (and its EclipseLink UnitOfWork identity map) can grow under sustained backlog - see
     * docs/plans/issue-77-bdactor-oom-fix.md.
     */
    private final int maxEnvelopesPerBatch;

    public ComponentMessageQueue(){
        this(Integer.MAX_VALUE);
    }

    public ComponentMessageQueue(int maxEnvelopesPerBatch) {
        this.maxEnvelopesPerBatch = maxEnvelopesPerBatch;
    }

    public void enqueue(ActorRef actorRef, Envelope envelope) {
        queue.offer(envelope);
    }

    public Envelope dequeue() {

        List list = new ArrayList<>();
        Envelope env = queue.peek();

        if (env != null && env.message() instanceof PoisonPill) {
            System.out.println("Poison pill found");
            return queue.poll();
        }

        // Only meaningful for the single-unrecognized-message case below - a batch of several
        // Stimulus/PersistenceState never had per-message sender tracking and still doesn't
        // (nothing reads it), but a lone control message (e.g. Flush) needs its ORIGINAL
        // sender preserved so an ask()-based reply (sender().tell(...)) actually reaches the
        // asker instead of deadLetters.
        Envelope singleUnrecognizedEnv = null;

        // Top-level envelopes merged into this batch so far - a whole PersistenceState[] array
        // counts as ONE, never split (see the atomicity comment below). Bounds how large a single
        // BDActor transaction (and its EclipseLink UnitOfWork identity map) can grow under
        // sustained backlog; Integer.MAX_VALUE (unbounded, component-dispatcher's default) never
        // trips this. See docs/plans/issue-77-bdactor-oom-fix.md.
        int envelopesMerged = 0;

        while (!queue.isEmpty()) {
            env = queue.peek();

            if (env.message() instanceof Stimulus || env.message() instanceof PersistenceState) {
                if (envelopesMerged >= maxEnvelopesPerBatch) break;
                list.add(env.message());
                queue.poll();
                envelopesMerged++;
            } else if (env.message() instanceof PersistenceState[]) {
                // A whole persist(states...) call, kept atomic (all-or-nothing in the same
                // dequeue()) - see CreatureComponent.persist()'s javadoc. Some states
                // reference each other (e.g. a @OneToOne join); splitting them across two
                // BDActor transactions previously caused a duplicate-primary-key crash when
                // the second transaction re-inserted an already-committed, now-detached
                // entity. Flattened into the same list as individual states so BDActor's
                // batch-persist loop doesn't need to know the difference. The batch-size cap
                // (above) is checked BEFORE polling, so a capped-out array is left whole in the
                // queue for the next dequeue() rather than being split.
                if (envelopesMerged >= maxEnvelopesPerBatch) break;
                for (Object state : (PersistenceState[]) env.message()) {
                    list.add(state);
                }
                queue.poll();
                envelopesMerged++;
            } else if (env.message() instanceof String) {
                queue.poll();
            } else if(env.message() instanceof PoisonPill) {
                System.out.println("Next message is a poison pill, handle previous messages first");
                break;
            } else {
                // Any other message type (e.g. a control message like Flush) is delivered as
                // its own single-element batch, never merged with a Stimulus/PersistenceState
                // batch already collected - this preserves FIFO ordering (everything queued
                // strictly before it is returned first, in an earlier dequeue() call) while
                // guaranteeing forward progress. Previously this branch didn't exist, so an
                // unrecognized message type at the head of the queue matched no case: the loop
                // never called queue.poll() and never broke, spinning the dispatcher thread
                // forever with no exception or log line.
                if (list.isEmpty()) {
                    singleUnrecognizedEnv = env;
                    list.add(env.message());
                    queue.poll();
                }
                break;
            }
        }

        ActorRef sender = (singleUnrecognizedEnv != null) ? singleUnrecognizedEnv.sender() : ActorRef.noSender();
        return Envelope.apply(list, sender);
    }

    public int numberOfMessages() {
        return queue.size();
    }

    public boolean hasMessages() {
        return !queue.isEmpty();
    }

    public void cleanUp(ActorRef owner, MessageQueue deadLetters) {
        for (Envelope handle : queue) {
            deadLetters.enqueue(owner, handle);
        }
    }
}
