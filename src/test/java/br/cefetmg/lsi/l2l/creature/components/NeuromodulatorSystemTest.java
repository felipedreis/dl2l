package br.cefetmg.lsi.l2l.creature.components;

import br.cefetmg.lsi.l2l.common.Constants;
import br.cefetmg.lsi.l2l.common.SequentialId;
import br.cefetmg.lsi.l2l.creature.common.ActionType;
import br.cefetmg.lsi.l2l.creature.testing.TestingHarness;
import br.cefetmg.lsi.l2l.stimuli.DopaminergicStimulus;
import br.cefetmg.lsi.l2l.stimuli.NeuromodulatorState;
import br.cefetmg.lsi.l2l.stimuli.NeuromodulatorTick;
import br.cefetmg.lsi.l2l.stimuli.OrexinergicStimulus;
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

    private static OrexinergicStimulus orexin(double release) {
        return new OrexinergicStimulus(SID, SID.next(), release);
    }

    private static double publishedOrexin(TestingHarness h) {
        NeuromodulatorState s = h.fullRecorder().lastOf(NeuromodulatorState.class);
        assertNotNull(s, "a NeuromodulatorState must be published after each update");
        return s.orexinTonic;
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

    // --- Orexin integrator ---

    @Test
    void orexin_accumulates_from_release() {
        TestingHarness h = TestingHarness.builder().build();

        h.inject(NeuromodulatorSystem.class, orexin(0.5));
        assertEquals(0.5, publishedOrexin(h), 1e-9);

        // Decay happens on tick (like DA reuptake); release accumulates on OrexinergicStimulus.
        h.inject(NeuromodulatorSystem.class, tick(0.0));   // orexin decays: 0.5 * OREXIN_DECAY
        h.inject(NeuromodulatorSystem.class, orexin(0.5)); // then adds: + 0.5
        assertEquals(0.5 * Constants.OREXIN_DECAY + 0.5, publishedOrexin(h), 1e-9);
    }

    @Test
    void orexin_decays_to_zero_without_input() {
        TestingHarness h = TestingHarness.builder().build();

        h.inject(NeuromodulatorSystem.class, orexin(1.0));
        // Decay is triggered by tick (no release → zero orexin release each tick)
        for (int i = 0; i < 300; i++) {
            h.inject(NeuromodulatorSystem.class, tick(0.0));
        }
        assertEquals(0.0, publishedOrexin(h), 1e-3);
    }

    @Test
    void orexin_converges_to_fixed_point_with_continuous_release() {
        TestingHarness h = TestingHarness.builder().build();

        // Real pipeline order per cycle: tick (decay) then OrexinergicStimulus (accumulate).
        // Fixed point: orexin = orexin * OREXIN_DECAY + 1.0 → orexin = 1 / (1 - OREXIN_DECAY).
        for (int i = 0; i < 400; i++) {
            h.inject(NeuromodulatorSystem.class, tick(0.0));
            h.inject(NeuromodulatorSystem.class, orexin(1.0));
        }
        double expected = 1.0 / (1.0 - Constants.OREXIN_DECAY);
        assertEquals(expected, publishedOrexin(h), 1.0);
    }

    // --- Tedium as a reward-absence affect regulated by the pool ---

    @Test
    void positive_dopamine_relieves_tedium() {
        TestingHarness h = TestingHarness.builder().build();
        h.creature().emotions().regulate(Constants.TEDIUM, 3.0);
        double before = h.creature().emotions().getLevel(Constants.TEDIUM);

        h.inject(NeuromodulatorSystem.class, dopamine(1.0)); // rpe>0 → relief = DA_TEDIUM_RELIEF*rpe

        double after = h.creature().emotions().getLevel(Constants.TEDIUM);
        assertEquals(before - Constants.DA_TEDIUM_RELIEF * 1.0, after, 1e-9);
        assertTrue(after < before, "a rewarding event should relieve boredom");
    }

    @Test
    void negative_dopamine_does_not_relieve_tedium() {
        TestingHarness h = TestingHarness.builder().build();
        h.creature().emotions().regulate(Constants.TEDIUM, 3.0);
        double before = h.creature().emotions().getLevel(Constants.TEDIUM);

        h.inject(NeuromodulatorSystem.class, dopamine(-1.0)); // worse than expected → no relief

        assertEquals(before, h.creature().emotions().getLevel(Constants.TEDIUM), 1e-9);
    }

    @Test
    void tick_raises_tedium_passively() {
        TestingHarness h = TestingHarness.builder().build();
        double before = h.creature().emotions().getLevel(Constants.TEDIUM);

        h.inject(NeuromodulatorSystem.class, tick(0.0));

        assertTrue(h.creature().emotions().getLevel(Constants.TEDIUM) > before,
                "with no reward, boredom should creep up each cycle");
    }
}
