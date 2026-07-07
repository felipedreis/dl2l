package br.cefetmg.lsi.l2l.creature.components;

import br.cefetmg.lsi.l2l.common.Constants;
import br.cefetmg.lsi.l2l.common.SequentialId;
import br.cefetmg.lsi.l2l.stimuli.DopaminergicStimulus;
import br.cefetmg.lsi.l2l.stimuli.NeuromodulatorState;
import br.cefetmg.lsi.l2l.stimuli.NeuromodulatorTick;
import br.cefetmg.lsi.l2l.stimuli.SerotonergicStimulus;
import br.cefetmg.lsi.l2l.stimuli.Stimulus;

import java.util.List;

/**
 * Untyped neuromodulator pool (VTA/raphe role) modelled as two leaky integrators — a tonic
 * dopamine and a tonic serotonin concentration. Neuromodulators arrive only as explicit
 * messages: {@link DopaminergicStimulus} (phasic reward-prediction error), {@link SerotonergicStimulus}
 * (satiety), and {@link NeuromodulatorTick} (per-cycle reuptake + circadian baseline synthesis).
 * The molecule *is* the message — there is no synchronous release/read API.
 *
 * <p>After each batch the current tonic levels are published as a {@link NeuromodulatorState} to
 * {@code FullAppraisal}, which uses them as slow-varying behavioural gains (exploration/patience).
 * Concentrations are floored at zero (a physical pool cannot go negative); a run of negative
 * prediction errors simply drives dopamine toward that floor.
 */
public class NeuromodulatorSystem extends CreatureComponent {

    private double dopamine = 0.0;
    private double serotonin = 0.0;
    private double lastPhasicDopamine = 0.0;

    public NeuromodulatorSystem(SequentialId id) {
        super(id);
    }

    @Override
    public void onReceive(Object message) {
        @SuppressWarnings("unchecked")
        List<Stimulus> stimuli = (List<Stimulus>) message;

        for (Stimulus stimulus : stimuli) {
            if (stimulus instanceof DopaminergicStimulus da) {
                lastPhasicDopamine = da.rpe;
                dopamine = clampFloor(dopamine + da.rpe);
            } else if (stimulus instanceof SerotonergicStimulus serotonergic) {
                serotonin = clampFloor(serotonin + serotonergic.satiety);
            } else if (stimulus instanceof NeuromodulatorTick tick) {
                dopamine  = clampFloor(dopamine  * Constants.DOPAMINE_DECAY  + baseline(Constants.DOPAMINE_BASELINE,  tick.circadianPhase));
                serotonin = clampFloor(serotonin * Constants.SEROTONIN_DECAY + baseline(Constants.SEROTONIN_BASELINE, tick.circadianPhase));
            }
        }

        publishState();
    }

    private void publishState() {
        creature.fullAppraisal().tell(
                new NeuromodulatorState(id, nextStimulusId(), dopamine, serotonin));
    }

    /** Baseline synthesis: a constant floor modulated by the circadian phase. */
    private static double baseline(double base, double circadianPhase) {
        return base + Constants.NEUROMODULATOR_CIRCADIAN_AMPLITUDE * Math.sin(circadianPhase);
    }

    private static double clampFloor(double v) {
        return Math.max(0.0, v);
    }

    // Package-private accessors for unit tests (the production readout is the published message).
    double dopamine()  { return dopamine; }
    double serotonin() { return serotonin; }
    double lastPhasicDopamine() { return lastPhasicDopamine; }
}
