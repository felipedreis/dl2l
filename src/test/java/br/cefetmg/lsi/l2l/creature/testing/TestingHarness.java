package br.cefetmg.lsi.l2l.creature.testing;

import br.cefetmg.lsi.l2l.cluster.settings.LearningSettings;
import br.cefetmg.lsi.l2l.common.Point;
import br.cefetmg.lsi.l2l.common.SequentialId;
import br.cefetmg.lsi.l2l.creature.bd.ActionSelectionType;
import br.cefetmg.lsi.l2l.creature.components.Body;
import br.cefetmg.lsi.l2l.creature.components.CreatureComponent;
import br.cefetmg.lsi.l2l.creature.components.EffectorCortex;
import br.cefetmg.lsi.l2l.creature.components.Eye;
import br.cefetmg.lsi.l2l.creature.components.FullAppraisal;
import br.cefetmg.lsi.l2l.creature.components.HomeostaticRegulation;
import br.cefetmg.lsi.l2l.creature.components.Mouth;
import br.cefetmg.lsi.l2l.creature.components.NeuromodulatorSystem;
import br.cefetmg.lsi.l2l.creature.components.Nose;
import br.cefetmg.lsi.l2l.creature.components.PartialAppraisal;
import br.cefetmg.lsi.l2l.creature.components.SensoryCortex;
import br.cefetmg.lsi.l2l.creature.components.Valuation;
import br.cefetmg.lsi.l2l.stimuli.LuminousStimulus;
import br.cefetmg.lsi.l2l.stimuli.MechanicalStimulus;
import br.cefetmg.lsi.l2l.stimuli.Stimulus;

import java.util.List;

/**
 * Top-level facade that builds and drives a {@link TestingCreature}.
 * <p>
 * Lifecycle:
 * <pre>
 *   TestingHarness h = TestingHarness.builder().build();
 *   h.injectLuminous(new LuminousStimulus(...));   // external stimulus
 *   h.tick();                                       // one cognitive cycle
 *   assertEquals(ActionType.APPROACH, h.lastChosenAction());
 * </pre>
 *
 * A {@code tick()} fires {@link PartialAppraisal} (the clock-driven entry point used
 * by the real {@code CreatureActor} scheduler) and then runs the dispatcher loop until
 * every {@link BatchingDispatcher} is empty.
 */
public final class TestingHarness {

    private final TestingCreature creature;

    private TestingHarness(TestingCreature creature) {
        this.creature = creature;
        creature.init();
    }

    public static Builder builder() {
        return new Builder();
    }

    public TestingCreature creature() { return creature; }

    public RecordingComponentRef recorderOf(Class<? extends CreatureComponent> type) {
        return creature.recorderOf(type);
    }

    // Convenience accessors for the most common assertions.
    public RecordingComponentRef eyeRecorder()           { return recorderOf(Eye.class); }
    public RecordingComponentRef bodyRecorder()          { return recorderOf(Body.class); }
    public RecordingComponentRef mouthRecorder()         { return recorderOf(Mouth.class); }
    public RecordingComponentRef noseRecorder()          { return recorderOf(Nose.class); }
    public RecordingComponentRef sensoryCortexRecorder() { return recorderOf(SensoryCortex.class); }
    public RecordingComponentRef effectorCortexRecorder(){ return recorderOf(EffectorCortex.class); }
    public RecordingComponentRef partialRecorder()       { return recorderOf(PartialAppraisal.class); }
    public RecordingComponentRef fullRecorder()          { return recorderOf(FullAppraisal.class); }
    public RecordingComponentRef homeostaticRecorder()   { return recorderOf(HomeostaticRegulation.class); }
    public RecordingComponentRef valuationRecorder()     { return recorderOf(Valuation.class); }
    public RecordingComponentRef neuromodulatorRecorder(){ return recorderOf(NeuromodulatorSystem.class); }

    public ExternalSink holderSink()             { return creature.holderSink(); }
    public ExternalSink memoryConsolidatorSink() { return creature.memoryConsolidatorSink(); }
    public ExternalSink bdSink()                 { return creature.bdSink(); }

    /** Drive one cognitive cycle: kick PartialAppraisal, then drain everything. */
    public void tick() {
        creature.dispatcherOf(PartialAppraisal.class).drain();
        processUntilQuiescent();
    }

    /** Run dispatchers until nothing is pending anywhere. Used after every external injection. */
    public void processUntilQuiescent() {
        boolean progressed;
        int guard = 0;
        do {
            progressed = false;
            for (BatchingDispatcher d : creature.allDispatchers()) {
                if (d.hasPending()) {
                    d.drain();
                    progressed = true;
                }
            }
            if (++guard > 10_000) {
                throw new IllegalStateException("processUntilQuiescent: dispatchers did not settle");
            }
        } while (progressed);
    }

    /** Inject a Luminous stimulus (vision input) directly into the eye. */
    public void injectLuminous(LuminousStimulus... stimuli) {
        for (LuminousStimulus s : stimuli) creature.eye().tell(s);
        processUntilQuiescent();
    }

    /** Inject any external stimulus into a named component (bypassing the recorder so the message isn't double-counted). */
    public void inject(Class<? extends CreatureComponent> target, Stimulus stimulus) {
        creature.dispatcherOf(target).tell(stimulus);
        processUntilQuiescent();
    }

    public void injectMechanical(MechanicalStimulus... stimuli) {
        for (MechanicalStimulus s : stimuli) creature.dispatcherOf(Mouth.class).tell(s);
        processUntilQuiescent();
    }

    public static final class Builder {
        private SequentialId id = new SequentialId(1L);
        private Point position = new Point(100, 100);
        private Point worldBoundaries = new Point(1000, 1000);
        private LearningSettings learningSettings = defaultLearningSettings();

        public Builder id(SequentialId id) { this.id = id; return this; }
        public Builder position(Point p) { this.position = p; return this; }
        public Builder worldBoundaries(Point p) { this.worldBoundaries = p; return this; }
        public Builder learningSettings(LearningSettings s) { this.learningSettings = s; return this; }

        public TestingHarness build() {
            return new TestingHarness(new TestingCreature(id, position, worldBoundaries, learningSettings));
        }

        /**
         * Test-friendly defaults: circadian on (sleep drive accumulates), consolidation
         * irrelevant in tests (TestingCreature always stubs the consolidator), filter chain
         * without WORLD_MODEL since we don't load DJL models.
         */
        public static LearningSettings defaultLearningSettings() {
            return new LearningSettings(
                    true,
                    false,
                    List.of(ActionSelectionType.TARGET_DISTANCE,
                            ActionSelectionType.AFFORDANCE,
                            ActionSelectionType.RANDOM));
        }
    }
}
