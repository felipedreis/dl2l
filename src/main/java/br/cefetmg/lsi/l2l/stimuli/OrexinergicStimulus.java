package br.cefetmg.lsi.l2l.stimuli;

import br.cefetmg.lsi.l2l.common.SequentialId;

/**
 * Per-cycle orexin release computed by {@link br.cefetmg.lsi.l2l.creature.components.PartialAppraisal}
 * and integrated by {@link br.cefetmg.lsi.l2l.creature.components.NeuromodulatorSystem}.
 *
 * {@code release} ∈ [0, 1]: high when sleep pressure is low (creature is rested), zero when
 * sleep pressure reaches MAX_AROUSAL_LEVEL. The tonic orexin level gates SLEEP out of the
 * action set while the creature is rested, and allows it back in when the creature is genuinely
 * sleepy — the two-process antagonism of Borbély's sleep model.
 *
 * Natural antagonist of {@link AdenosinergicStimulus}.
 */
public class OrexinergicStimulus extends Stimulus {

    public final double release;

    public OrexinergicStimulus(SequentialId origin, SequentialId stimulusId, double release) {
        super(origin, stimulusId);
        this.release = release;
    }
}
