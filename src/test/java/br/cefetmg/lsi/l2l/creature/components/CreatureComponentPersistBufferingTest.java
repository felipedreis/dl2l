package br.cefetmg.lsi.l2l.creature.components;

import br.cefetmg.lsi.l2l.common.Point;
import br.cefetmg.lsi.l2l.common.SequentialId;
import br.cefetmg.lsi.l2l.creature.ComponentRef;
import br.cefetmg.lsi.l2l.creature.Creature;
import br.cefetmg.lsi.l2l.creature.bd.EmotionalState;
import br.cefetmg.lsi.l2l.creature.bd.PersistenceState;
import br.cefetmg.lsi.l2l.creature.conditioning.OperantConditioning;
import br.cefetmg.lsi.l2l.creature.conditioning.expectancy.ExpectancyPredictor;
import br.cefetmg.lsi.l2l.creature.memory.MemorySystem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the producer-side {@code persist()} buffering added in
 * docs/plans/arrow-ipc-write-path.md, W4 - a plain {@link CreatureComponent} subclass wired to
 * a recording {@code bd()} sink, exercised without any Akka machinery (mirrors
 * {@code ComponentMessageQueueTest}'s no-ActorSystem style). Complements
 * {@code br.cefetmg.lsi.l2l.creature.testing.BdSinkFunctionalTest}, which now runs with
 * buffering forced off ({@link CreatureComponent#setPersistBufferThresholdForTesting}) so its
 * single-stimulus assertions stay synchronous.
 */
class CreatureComponentPersistBufferingTest {

    /** Records every message {@code bd()} would receive, flattening arrays like BDActor's mailbox does. */
    private static final class RecordingRef implements ComponentRef {
        final List<PersistenceState> flattened = new ArrayList<>();
        int tellCount = 0;

        @Override
        public void tell(Object msg) {
            tellCount++;
            if (msg instanceof PersistenceState[] arr) {
                for (PersistenceState s : arr) flattened.add(s);
            }
        }
    }

    /** Every method but {@link #bd()} is unused by these tests. */
    private static final class FakeCreature implements Creature {
        private final ComponentRef bd;

        FakeCreature(ComponentRef bd) {
            this.bd = bd;
        }

        @Override public ComponentRef bd() { return bd; }

        @Override public void init() { }
        @Override public ComponentRef holder() { throw unused(); }
        @Override public ComponentRef eye() { throw unused(); }
        @Override public ComponentRef body() { throw unused(); }
        @Override public ComponentRef mouth() { throw unused(); }
        @Override public ComponentRef nose() { throw unused(); }
        @Override public ComponentRef sensoryCortex() { throw unused(); }
        @Override public ComponentRef effectorCortex() { throw unused(); }
        @Override public ComponentRef partialAppraisal() { throw unused(); }
        @Override public ComponentRef fullAppraisal() { throw unused(); }
        @Override public ComponentRef homeostatic() { throw unused(); }
        @Override public ComponentRef valuation() { throw unused(); }
        @Override public ComponentRef neuromodulators() { throw unused(); }
        @Override public ComponentRef endocrine() { throw unused(); }
        @Override public EmotionalSystem emotions() { throw unused(); }
        @Override public OperantConditioning operantConditioning() { throw unused(); }
        @Override public MemorySystem memory() { throw unused(); }
        @Override public ExpectancyPredictor expectancy() { throw unused(); }
        @Override public ComponentRef memoryConsolidator() { throw unused(); }
        @Override public void kill() { throw unused(); }
        @Override public void tick() { throw unused(); }
        @Override public void setAlive(boolean alive) { throw unused(); }
        @Override public boolean isAlive() { throw unused(); }
        @Override public void setPosition(Point point) { throw unused(); }
        @Override public Point getPosition() { throw unused(); }
        @Override public void setVisionFieldOpening(double opening) { throw unused(); }
        @Override public double getVisionFieldOpening() { throw unused(); }
        @Override public void setVisionFieldPosition(double arc) { throw unused(); }
        @Override public double getVisionFieldPosition() { throw unused(); }
        @Override public void setOlfactoryFieldRadius(double radius) { throw unused(); }
        @Override public double getOlfactoryFieldRadius() { throw unused(); }

        private static UnsupportedOperationException unused() {
            return new UnsupportedOperationException("not used by CreatureComponentPersistBufferingTest");
        }
    }

    /** Minimal concrete component so the test can call the protected persist() directly. */
    private static final class TestComponent extends CreatureComponent {
        TestComponent(SequentialId id) {
            super(id);
        }

        @Override
        public void onReceive(Object message) {
        }

        void doPersist(PersistenceState... states) {
            persist(states);
        }
    }

    private RecordingRef bdRef;
    private TestComponent component;

    @BeforeEach
    void init() {
        bdRef = new RecordingRef();
        component = new TestComponent(new SequentialId(1));
        component.init(new FakeCreature(bdRef), null);
    }

    @Test
    void belowThresholdStatesStayBufferedUntilFlush() {
        component.setPersistBufferThresholdForTesting(4);

        component.doPersist(new EmotionalState());
        component.doPersist(new EmotionalState());

        assertEquals(0, bdRef.tellCount, "buffer hasn't reached threshold yet, nothing sent");
        assertTrue(bdRef.flattened.isEmpty());
    }

    @Test
    void reachingThresholdFlushesExactlyOneConsolidatedArray() {
        component.setPersistBufferThresholdForTesting(3);

        component.doPersist(new EmotionalState());
        component.doPersist(new EmotionalState());
        component.doPersist(new EmotionalState()); // hits threshold=3

        assertEquals(1, bdRef.tellCount, "threshold-reaching batch must be ONE tell(), not three");
        assertEquals(3, bdRef.flattened.size());
    }

    @Test
    void bufferResetsAfterFlushAndAccumulatesAgain() {
        component.setPersistBufferThresholdForTesting(2);

        component.doPersist(new EmotionalState(), new EmotionalState()); // flushes (2 states, 1 persist() call)
        assertEquals(1, bdRef.tellCount);

        component.doPersist(new EmotionalState()); // starts a new, sub-threshold buffer
        assertEquals(1, bdRef.tellCount, "second persist() must not have flushed yet");
        assertEquals(2, bdRef.flattened.size(), "the new buffered state must not be sent yet");
    }

    @Test
    void zeroThresholdIsPassthrough() {
        component.setPersistBufferThresholdForTesting(0);

        component.doPersist(new EmotionalState());
        assertEquals(1, bdRef.tellCount, "N=0 must tell() immediately, once per persist() call");

        component.doPersist(new EmotionalState());
        assertEquals(2, bdRef.tellCount);
    }

    @Test
    void flushPersistBufferSendsPendingSubThresholdStatesAndIsIdempotent() {
        component.setPersistBufferThresholdForTesting(256); // default-sized, well above what's buffered here

        component.doPersist(new EmotionalState());
        assertEquals(0, bdRef.tellCount);

        component.flushPersistBuffer(); // e.g. ComponentActor.postStop()'s call
        assertEquals(1, bdRef.tellCount);
        assertEquals(1, bdRef.flattened.size());

        component.flushPersistBuffer(); // nothing pending - must not send an empty array
        assertEquals(1, bdRef.tellCount, "flushing an empty buffer must be a no-op");
    }

    @Test
    void switchingThresholdForTestingFlushesWhateverWasPendingFirst() {
        component.setPersistBufferThresholdForTesting(10);
        component.doPersist(new EmotionalState(), new EmotionalState());
        assertEquals(0, bdRef.tellCount);

        component.setPersistBufferThresholdForTesting(0); // must not silently drop the 2 pending states

        assertEquals(1, bdRef.tellCount);
        assertEquals(2, bdRef.flattened.size());
    }
}
