package br.cefetmg.lsi.l2l.stimuli;

import br.cefetmg.lsi.l2l.common.SequentialId;

/**
 * Per-cycle pacemaker tick for {@link br.cefetmg.lsi.l2l.creature.components.NeuromodulatorSystem}:
 * drives reuptake (multiplicative decay) of every neuromodulator pool plus circadian-modulated
 * baseline synthesis. Emitted by {@code PartialAppraisal} alongside the other tonic release events.
 * {@code circadianPhase} is the current oscillator phase in radians.
 */
public class NeuromodulatorTick extends Stimulus {

    public final double circadianPhase;

    public NeuromodulatorTick(SequentialId origin, SequentialId stimulusId, double circadianPhase) {
        super(origin, stimulusId);
        this.circadianPhase = circadianPhase;
    }
}
