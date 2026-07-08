package br.cefetmg.lsi.l2l.stimuli;

import br.cefetmg.lsi.l2l.common.SequentialId;

/**
 * HPA-axis cortisol contribution routed to {@link br.cefetmg.lsi.l2l.creature.components.EndocrineSystem}.
 * Two emitters:
 * <ul>
 *   <li>{@code PartialAppraisal} — circadian morning pulse (once per phase wrap)</li>
 *   <li>{@code HomeostaticRegulation} — chronic stressor pathway (when a drive arousal
 *       exceeds {@code STRESS_ACTIVATION_THRESHOLD})</li>
 * </ul>
 * {@code magnitude} is additive; the endocrine integrator decays it slowly
 * (half-life ≈ 1386 cycles).
 */
public class CortisolStimulus extends Stimulus {

    public final double magnitude;

    public CortisolStimulus(SequentialId origin, SequentialId stimulusId, double magnitude) {
        super(origin, stimulusId);
        this.magnitude = magnitude;
    }
}
