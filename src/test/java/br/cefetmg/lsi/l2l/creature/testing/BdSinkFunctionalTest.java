package br.cefetmg.lsi.l2l.creature.testing;

import br.cefetmg.lsi.l2l.common.Point;
import br.cefetmg.lsi.l2l.common.SequentialId;
import br.cefetmg.lsi.l2l.creature.bd.BehaviouralEfficiencyState;
import br.cefetmg.lsi.l2l.creature.bd.ChangeStimulusState;
import br.cefetmg.lsi.l2l.creature.bd.ObjectSeenState;
import br.cefetmg.lsi.l2l.stimuli.LuminousStimulus;
import br.cefetmg.lsi.l2l.world.FruitType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression net for the persist() rewire (docs/plans/bdactor-async-persistence-with-drain.md):
 * creature.bd().tell(state) - which TestingCreature already wired to an ExternalSink - was
 * present but never asserted on before this change, since persistence used to flow through
 * the injected Persister instead. Covers both persist-call-site categories: a one-off
 * transition (Eye, triggered by a specific stimulus) and a recurring, every-cycle log
 * (PartialAppraisal.persistCycle, fired unconditionally on every tick()).
 */
class BdSinkFunctionalTest {

    @Test
    void oneOffTransition_luminousStimulusReachesBdSink() {
        TestingHarness h = TestingHarness.builder().build();
        SequentialId origin = new SequentialId(90_001L);

        h.injectLuminous(new LuminousStimulus(origin, origin.next(), FruitType.RED_APPLE,
                new Point(h.creature().getPosition().x + 50, h.creature().getPosition().y)));

        assertFalse(h.bdSink().ofType(ObjectSeenState.class).isEmpty(),
                "Eye's LuminousStimulus handler should have persisted an ObjectSeenState");
        assertFalse(h.bdSink().ofType(ChangeStimulusState.class).isEmpty(),
                "Eye's LuminousStimulus handler should have persisted a ChangeStimulusState");
    }

    @Test
    void recurringLog_everyTickReachesBdSink() {
        TestingHarness h = TestingHarness.builder().build();

        h.tick();

        assertFalse(h.bdSink().ofType(BehaviouralEfficiencyState.class).isEmpty(),
                "PartialAppraisal.persistCycle() runs unconditionally on every tick()");
        assertFalse(h.bdSink().ofType(ChangeStimulusState.class).isEmpty(),
                "PartialAppraisal.persistCycle() runs unconditionally on every tick()");
    }
}
