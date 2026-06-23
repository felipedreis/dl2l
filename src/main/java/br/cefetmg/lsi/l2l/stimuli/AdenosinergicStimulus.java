package br.cefetmg.lsi.l2l.stimuli;

import br.cefetmg.lsi.l2l.common.SequentialId;

/**
 * Carries the adenosine-mediated sleep-drive increment for one cognitive cycle.
 * Adenosine accumulates during wakefulness and is the primary homeostatic signal
 * for sleep pressure (Process S in the two-process model). Its accumulation rate
 * is modulated by CircadianClock, mirroring how the SCN gates adenosine sensitivity.
 *
 * Handled by HomeostaticRegulation as a sleep-only regulation; no EvaluationStimulus
 * is emitted because drive accumulation is not a reinforceable event.
 *
 * Natural antagonist of CholinergicStimulus (wakefulness-promoting).
 */
public class AdenosinergicStimulus extends Stimulus {

    public final double delta;

    public AdenosinergicStimulus(SequentialId origin, SequentialId stimulusId, double delta) {
        super(origin, stimulusId);
        this.delta = delta;
    }
}
