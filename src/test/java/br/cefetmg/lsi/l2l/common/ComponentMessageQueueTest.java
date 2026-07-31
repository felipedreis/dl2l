package br.cefetmg.lsi.l2l.common;

import akka.actor.ActorRef;
import akka.actor.PoisonPill;
import akka.dispatch.Envelope;
import br.cefetmg.lsi.l2l.creature.bd.PersistenceState;
import br.cefetmg.lsi.l2l.stimuli.NeuromodulatorTick;
import br.cefetmg.lsi.l2l.stimuli.Stimulus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * No ActorSystem needed - ComponentMessageQueue only depends on akka.dispatch types used as
 * plain data (Envelope/ActorRef.noSender()), same style as SimulationSettingsExtensionTest.
 */
public class ComponentMessageQueueTest {

    private static final class FakeState implements PersistenceState {
    }

    private ComponentMessageQueue queue;

    @BeforeEach
    public void init() {
        queue = new ComponentMessageQueue();
    }

    private void enqueue(Object message) {
        queue.enqueue(ActorRef.noSender(), Envelope.apply(message, ActorRef.noSender()));
    }

    private Stimulus stimulus() {
        return new NeuromodulatorTick(new SequentialId(1), new SequentialId(2), 0.0);
    }

    @Test
    public void batchesStimuli() {
        Stimulus a = stimulus();
        Stimulus b = stimulus();
        enqueue(a);
        enqueue(b);

        List<?> batch = (List<?>) queue.dequeue().message();

        assertEquals(List.of(a, b), batch);
        assertFalse(queue.hasMessages());
    }

    @Test
    public void batchesPersistenceStates() {
        FakeState a = new FakeState();
        FakeState b = new FakeState();
        enqueue(a);
        enqueue(b);

        List<?> batch = (List<?>) queue.dequeue().message();

        assertEquals(List.of(a, b), batch);
    }

    @Test
    public void persistStateArraysAreFlattenedIntoTheSameBatchAtomically() {
        // Regression test: CreatureComponent.persist(states...) sends the whole array as ONE
        // message specifically so states that reference each other (e.g. a @OneToOne join)
        // always land in the same BDActor transaction, never split across two. Confirmed
        // live: splitting them (an earlier version sent one .tell() per state) caused a
        // duplicate-primary-key crash when a later transaction re-inserted an
        // already-committed, now-detached referenced entity.
        FakeState a = new FakeState();
        FakeState b = new FakeState();
        enqueue(new PersistenceState[]{a, b});

        List<?> batch = (List<?>) queue.dequeue().message();

        assertEquals(List.of(a, b), batch);
        assertFalse(queue.hasMessages());
    }

    @Test
    public void discardsStrings() {
        enqueue("not a real message");
        Stimulus a = stimulus();
        enqueue(a);

        List<?> batch = (List<?>) queue.dequeue().message();

        assertEquals(List.of(a), batch);
    }

    @Test
    public void poisonPillAtHeadIsDeliveredBare() {
        enqueue(PoisonPill.getInstance());

        Envelope env = queue.dequeue();

        assertInstanceOf(PoisonPill.class, env.message());
    }

    @Test
    public void poisonPillAfterMessagesIsHandledAfterThem() {
        Stimulus a = stimulus();
        enqueue(a);
        enqueue(PoisonPill.getInstance());

        List<?> firstBatch = (List<?>) queue.dequeue().message();
        assertEquals(List.of(a), firstBatch);

        Envelope second = queue.dequeue();
        assertInstanceOf(PoisonPill.class, second.message());
    }

    @Test
    public void unrecognizedMessageIsDeliveredAsItsOwnSingleElementBatch() {
        Object unrecognized = new Object();
        enqueue(unrecognized);

        List<?> batch = (List<?>) queue.dequeue().message();

        assertEquals(List.of(unrecognized), batch);
        assertFalse(queue.hasMessages());
    }

    @Test
    public void unrecognizedMessageDeliveryPreservesItsOriginalSender() {
        // Regression test: BDActor's Flush/FlushAck ask-based drain protocol relies on
        // sender() inside onReceive resolving to the actual asker, not deadLetters. Before
        // this fix, dequeue() always returned Envelope.apply(list, ActorRef.noSender()),
        // silently discarding the original sender - so a Flush reply went nowhere and the
        // ask() timed out (confirmed live via a docker-compose smoke test). Uses
        // system.deadLetters() as the "original sender" specifically because it's a
        // concrete ActorRef distinct from ActorRef.noSender() - asserting against noSender()
        // itself wouldn't catch a regression back to the old hardcoded default.
        // "local" provider (not the default ClusterActorRefProvider from application.conf)
        // keeps this to a plain in-process ActorSystem - no cluster join attempt, no remoting.
        com.typesafe.config.Config localOnly =
                com.typesafe.config.ConfigFactory.parseString("akka.actor.provider = local");
        akka.actor.ActorSystem system = akka.actor.ActorSystem.create("component-message-queue-test", localOnly);
        try {
            ActorRef originalSender = system.deadLetters();
            Object flushLike = new Object();
            queue.enqueue(ActorRef.noSender(), Envelope.apply(flushLike, originalSender));

            Envelope env = queue.dequeue();

            assertEquals(originalSender, env.sender());
            assertNotEquals(ActorRef.noSender(), env.sender());
        } finally {
            system.terminate();
        }
    }

    @Test
    public void unrecognizedMessageDoesNotHangAndDoesNotMergeWithAPriorBatch() {
        Stimulus a = stimulus();
        Object unrecognized = new Object();
        enqueue(a);
        enqueue(unrecognized);

        // First dequeue() must return only the prior batch, leaving the unrecognized
        // message queued - regression test for the missing-default-branch bug where this
        // combination spun the dispatcher thread forever instead of returning at all.
        List<?> firstBatch = (List<?>) queue.dequeue().message();
        assertEquals(List.of(a), firstBatch);
        assertTrue(queue.hasMessages());

        List<?> secondBatch = (List<?>) queue.dequeue().message();
        assertEquals(List.of(unrecognized), secondBatch);
        assertFalse(queue.hasMessages());
    }

    // --- Bounded batch size (docs/plans/issue-77-bdactor-oom-fix.md) ---

    private void enqueueOn(ComponentMessageQueue q, Object message) {
        q.enqueue(ActorRef.noSender(), Envelope.apply(message, ActorRef.noSender()));
    }

    @Test
    public void capLimitsNumberOfTopLevelEnvelopesMergedPerDequeue() {
        ComponentMessageQueue capped = new ComponentMessageQueue(2);
        FakeState a = new FakeState();
        FakeState b = new FakeState();
        FakeState c = new FakeState();
        enqueueOn(capped, a);
        enqueueOn(capped, b);
        enqueueOn(capped, c);

        List<?> firstBatch = (List<?>) capped.dequeue().message();
        assertEquals(List.of(a, b), firstBatch, "first dequeue() should stop at the cap");
        assertTrue(capped.hasMessages(), "the remainder should stay queued, not be dropped");

        List<?> secondBatch = (List<?>) capped.dequeue().message();
        assertEquals(List.of(c), secondBatch);
        assertFalse(capped.hasMessages());
    }

    @Test
    public void capNeverSplitsAPersistenceStateArrayAcrossDequeues() {
        // cap=1: a lone state fills the cap, so a subsequent whole array must be deferred
        // entirely to the next dequeue() rather than being partially consumed - splitting an
        // array's states across two BDActor transactions previously caused a duplicate-
        // primary-key crash (see persistStateArraysAreFlattenedIntoTheSameBatchAtomically).
        ComponentMessageQueue capped = new ComponentMessageQueue(1);
        FakeState lone = new FakeState();
        FakeState arrayA = new FakeState();
        FakeState arrayB = new FakeState();
        FakeState arrayC = new FakeState();
        enqueueOn(capped, lone);
        enqueueOn(capped, new PersistenceState[]{arrayA, arrayB, arrayC});

        List<?> firstBatch = (List<?>) capped.dequeue().message();
        assertEquals(List.of(lone), firstBatch);
        assertTrue(capped.hasMessages());

        List<?> secondBatch = (List<?>) capped.dequeue().message();
        assertEquals(List.of(arrayA, arrayB, arrayC), secondBatch,
                "the whole array must arrive together, never split across two dequeue() calls");
        assertFalse(capped.hasMessages());
    }

    @Test
    public void unboundedConstructorHandlesALargeBacklogInOneDequeue() {
        // Regression guard: component-dispatcher (unset max-batch-size, defaults to
        // Integer.MAX_VALUE) must keep today's unbounded-drain behavior.
        ComponentMessageQueue unbounded = new ComponentMessageQueue();
        int n = 10_000;
        for (int i = 0; i < n; i++) {
            enqueueOn(unbounded, new FakeState());
        }

        List<?> batch = (List<?>) unbounded.dequeue().message();

        assertEquals(n, batch.size());
        assertFalse(unbounded.hasMessages());
    }

    @Test
    public void flushIsOnlyReturnedAloneAfterAllPriorCappedBatchesAreDrained() {
        ComponentMessageQueue capped = new ComponentMessageQueue(2);
        FakeState a = new FakeState();
        FakeState b = new FakeState();
        FakeState c = new FakeState();
        FakeState d = new FakeState();
        Object flushLike = new Object();
        enqueueOn(capped, a);
        enqueueOn(capped, b);
        enqueueOn(capped, c);
        enqueueOn(capped, d);
        enqueueOn(capped, flushLike);

        List<?> firstBatch = (List<?>) capped.dequeue().message();
        assertEquals(List.of(a, b), firstBatch);
        assertTrue(capped.hasMessages());

        List<?> secondBatch = (List<?>) capped.dequeue().message();
        assertEquals(List.of(c, d), secondBatch);
        assertTrue(capped.hasMessages());

        List<?> thirdBatch = (List<?>) capped.dequeue().message();
        assertEquals(List.of(flushLike), thirdBatch,
                "Flush must never be merged into a capped-out batch - it's only ever returned "
                        + "alone, once every prior batch has drained through its own dequeue()");
        assertFalse(capped.hasMessages());
    }

    // --- Bounded total states, independent of the envelope cap
    // (docs/plans/issue-77-bdactor-oom-fix-followup.md) ---

    @Test
    public void stateCountCapStopsMergingEvenWithEnvelopeCapNotYetReached() {
        // envelope cap is generous (10), state cap (3) is what should actually stop this batch -
        // each envelope here is a 2-state array, so the envelope cap alone would never have
        // caught a batch this large. The cap is checked BEFORE consuming an array (same
        // atomicity tradeoff as maxEnvelopesPerBatch), so listSize can briefly exceed the cap
        // by up to one array's worth - it stops merging on the NEXT array once already at/over
        // the cap, not mid-array.
        ComponentMessageQueue capped = new ComponentMessageQueue(10, 3);
        FakeState a1 = new FakeState(), a2 = new FakeState();
        FakeState b1 = new FakeState(), b2 = new FakeState();
        FakeState c1 = new FakeState(), c2 = new FakeState();
        enqueueOn(capped, new PersistenceState[]{a1, a2});
        enqueueOn(capped, new PersistenceState[]{b1, b2});
        enqueueOn(capped, new PersistenceState[]{c1, c2});

        List<?> firstBatch = (List<?>) capped.dequeue().message();
        assertEquals(List.of(a1, a2, b1, b2), firstBatch,
                "listSize hits 2 after the first array (under the cap of 3), so a second array "
                        + "is still merged, overshooting to 4; only the THIRD array is deferred");
        assertTrue(capped.hasMessages());

        List<?> secondBatch = (List<?>) capped.dequeue().message();
        assertEquals(List.of(c1, c2), secondBatch);
        assertFalse(capped.hasMessages());
    }

    @Test
    public void stateCountCapNeverSplitsAPersistenceStateArray() {
        ComponentMessageQueue capped = new ComponentMessageQueue(10, 1);
        FakeState lone = new FakeState();
        FakeState arrayA = new FakeState(), arrayB = new FakeState();
        enqueueOn(capped, lone);
        enqueueOn(capped, new PersistenceState[]{arrayA, arrayB});

        List<?> firstBatch = (List<?>) capped.dequeue().message();
        assertEquals(List.of(lone), firstBatch);

        List<?> secondBatch = (List<?>) capped.dequeue().message();
        assertEquals(List.of(arrayA, arrayB), secondBatch,
                "the whole array must arrive together even though it exceeds the state cap by "
                        + "itself - never split across two dequeue() calls");
    }

    @Test
    public void unboundedTwoArgConstructorPreservesUnboundedBehavior() {
        ComponentMessageQueue unbounded = new ComponentMessageQueue(Integer.MAX_VALUE, Integer.MAX_VALUE);
        int n = 10_000;
        for (int i = 0; i < n; i++) {
            enqueueOn(unbounded, new FakeState());
        }

        List<?> batch = (List<?>) unbounded.dequeue().message();

        assertEquals(n, batch.size());
        assertFalse(unbounded.hasMessages());
    }
}
