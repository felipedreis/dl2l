package br.cefetmg.lsi.l2l.stimuli;

import br.cefetmg.lsi.l2l.common.SequentialId;

/**
 * Published by {@link br.cefetmg.lsi.l2l.creature.components.EndocrineSystem} after each
 * cortisol update. Consumers (e.g. {@code FullAppraisal}) cache the latest values for
 * logging; the STRESS affect is regulated directly on {@code creature.emotions()} by the
 * endocrine system itself rather than being delegated via this message.
 */
public class EndocrineState extends Stimulus {

    public final double cortisolTonic;
    public final double stressLevel;

    public EndocrineState(SequentialId origin, SequentialId stimulusId,
                          double cortisolTonic, double stressLevel) {
        super(origin, stimulusId);
        this.cortisolTonic = cortisolTonic;
        this.stressLevel   = stressLevel;
    }
}
