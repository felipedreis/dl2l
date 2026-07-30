package br.cefetmg.lsi.l2l.common;

import akka.actor.ActorRef;
import akka.dispatch.Envelope;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.junit.jupiter.api.Test;
import scala.Option;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ComponentMailbox is instantiated once per dispatcher that references it (Akka passes that
 * dispatcher's own config subtree to the (Settings, Config) constructor - the same mechanism
 * Akka's own BoundedMailbox uses for "mailbox-capacity"), so bd-dispatcher can set
 * max-batch-size while component-dispatcher (no such key) stays unbounded. See
 * docs/plans/issue-77-bdactor-oom-fix.md.
 */
public class ComponentMailboxTest {

    private static final class FakeState implements br.cefetmg.lsi.l2l.creature.bd.PersistenceState {
    }

    private ComponentMessageQueue createQueue(Config config) {
        ComponentMailbox mailbox = new ComponentMailbox(null, config);
        return (ComponentMessageQueue) mailbox.create(Option.empty(), Option.empty());
    }

    @Test
    public void createEnforcesMaxBatchSizeWhenConfigured() {
        Config config = ConfigFactory.parseString("max-batch-size = 2");
        ComponentMessageQueue queue = createQueue(config);

        FakeState a = new FakeState();
        FakeState b = new FakeState();
        FakeState c = new FakeState();
        queue.enqueue(ActorRef.noSender(), Envelope.apply(a, ActorRef.noSender()));
        queue.enqueue(ActorRef.noSender(), Envelope.apply(b, ActorRef.noSender()));
        queue.enqueue(ActorRef.noSender(), Envelope.apply(c, ActorRef.noSender()));

        List<?> firstBatch = (List<?>) queue.dequeue().message();
        assertEquals(List.of(a, b), firstBatch);
        assertTrue(queue.hasMessages());
    }

    @Test
    public void createStaysUnboundedWhenMaxBatchSizeIsNotConfigured() {
        Config config = ConfigFactory.empty();
        ComponentMessageQueue queue = createQueue(config);

        int n = 5_000;
        for (int i = 0; i < n; i++) {
            queue.enqueue(ActorRef.noSender(), Envelope.apply(new FakeState(), ActorRef.noSender()));
        }

        List<?> batch = (List<?>) queue.dequeue().message();
        assertEquals(n, batch.size());
    }
}
