package br.cefetmg.lsi.l2l.creature.components;

import br.cefetmg.lsi.l2l.common.Constants;
import br.cefetmg.lsi.l2l.common.SequentialId;
import br.cefetmg.lsi.l2l.creature.common.ActionType;
import br.cefetmg.lsi.l2l.creature.testing.TestingHarness;
import br.cefetmg.lsi.l2l.stimuli.DopaminergicStimulus;
import br.cefetmg.lsi.l2l.stimuli.NeuromodulatorState;
import br.cefetmg.lsi.l2l.stimuli.NeuromodulatorTick;
import br.cefetmg.lsi.l2l.stimuli.SerotonergicStimulus;
import br.cefetmg.lsi.l2l.world.FruitType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the neuromodulator pool: leaky accumulation of phasic release stimuli, per-tick
 * reuptake with circadian-modulated baseline, and publication of tonic levels to FullAppraisal.
 */
public class NeuromodulatorSystemTest {

    private static final SequentialId SID = new SequentialId(999L);

    private static DopaminergicStimulus dopamine(double rpe) {
        return new DopaminergicStimulus(SID, SID.next(), rpe, FruitType.RED_APPLE, ActionType.EAT);
    }

    private static SerotonergicStimulus serotonin(double satiety) {
        return new SerotonergicStimulus(SID, SID.next(), satiety);
    }

    private static NeuromodulatorTick tick(double phase) {
        return new NeuromodulatorTick(SID, SID.next(), phase);
    }

    private static double publishedDopamine(TestingHarness h) {
        NeuromodulatorState s = h.fullRecorder().lastOf(NeuromodulatorState.class);
        assertNotNull(s, "a NeuromodulatorState must be published after each update");
        return s.dopamineTonic;
    }

    private static double publishedSerotonin(TestingHarness h) {
        NeuromodulatorState s = h.fullRecorder().lastOf(NeuromodulatorState.class);
        assertNotNull(s, "a NeuromodulatorState must be published after each update");
        return s.serotoninTonic;
    }

    @Test
    void phasic_release_accumulates_dopamine_and_publishes_state() {
        TestingHarness h = TestingHarness.builder().build();

        h.inject(NeuromodulatorSystem.class, dopamine(2.0));
        assertEquals(2.0, publishedDopamine(h), 1e-9);

        h.inject(NeuromodulatorSystem.class, dopamine(1.5));
        assertEquals(3.5, publishedDopamine(h), 1e-9);
    }

    @Test
    void serotonin_accumulates_from_satiety_releases() {
        TestingHarness h = TestingHarness.builder().build();

        h.inject(NeuromodulatorSystem.class, serotonin(0.4));
        h.inject(NeuromodulatorSystem.class, serotonin(0.6));

        assertEquals(1.0, publishedSerotonin(h), 1e-9);
    }

    @Test
    void tick_decays_dopamine_toward_zero_baseline() {
        TestingHarness h = TestingHarness.builder().build();

        h.inject(NeuromodulatorSystem.class, dopamine(5.0));
        assertEquals(5.0, publishedDopamine(h), 1e-9);

        // Phase 0 → sin(0)=0 → zero baseline; repeated reuptake drives dopamine toward 0.
        for (int i = 0; i < 300; i++) {
            h.inject(NeuromodulatorSystem.class, tick(0.0));
        }
        assertEquals(0.0, publishedDopamine(h), 1e-5);
    }

    @Test
    void circadian_baseline_converges_to_positive_fixed_point_at_peak_phase() {
        TestingHarness h = TestingHarness.builder().build();

        // Phase π/2 → sin=1 → baseline = amplitude; fixed point = amp/(1-decay).
        for (int i = 0; i < 400; i++) {
            h.inject(NeuromodulatorSystem.class, tick(Math.PI / 2));
        }
        double expected = Constants.NEUROMODULATOR_CIRCADIAN_AMPLITUDE / (1 - Constants.DOPAMINE_DECAY);
        assertEquals(expected, publishedDopamine(h), 1e-3);
    }

    @Test
    void circadian_baseline_floors_at_zero_at_trough_phase() {
        TestingHarness h = TestingHarness.builder().build();

        // Phase -π/2 → sin=-1 → negative baseline contribution; concentration floored at 0.
        for (int i = 0; i < 200; i++) {
            h.inject(NeuromodulatorSystem.class, tick(-Math.PI / 2));
        }
        assertEquals(0.0, publishedDopamine(h), 1e-9);
    }
}
