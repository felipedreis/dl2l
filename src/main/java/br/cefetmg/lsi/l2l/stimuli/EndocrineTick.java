package br.cefetmg.lsi.l2l.stimuli;

import br.cefetmg.lsi.l2l.common.SequentialId;

/**
 * Per-cycle pacemaker tick for {@link br.cefetmg.lsi.l2l.creature.components.EndocrineSystem}:
 * drives passive cortisol clearance (multiplicative decay) and circadian-modulated baseline
 * synthesis with negative feedback. Emitted by {@code PartialAppraisal} each cognitive cycle
 * when endocrineEnabled. {@code circadianPhase} is the current oscillator phase in radians.
 */
public class EndocrineTick extends Stimulus {

    public final double circadianPhase;

    public EndocrineTick(SequentialId origin, SequentialId stimulusId, double circadianPhase) {
        super(origin, stimulusId);
        this.circadianPhase = circadianPhase;
    }
}
