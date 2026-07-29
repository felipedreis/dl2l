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
}
