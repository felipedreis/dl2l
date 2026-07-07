package br.cefetmg.lsi.l2l.stimuli;

import br.cefetmg.lsi.l2l.common.SequentialId;

/**
 * A serotonin release event carrying a satiety quantum emitted each cycle by
 * {@code PartialAppraisal} (the raphe role). Satiety reflects how deeply the active drives sit
 * inside Mapa's homeostatic equilibrium band; higher satiety raises tonic serotonin, which biases
 * behaviour toward patience and quieting (rest/observe). Integrated by
 * {@link br.cefetmg.lsi.l2l.creature.components.NeuromodulatorSystem}.
 */
public class SerotonergicStimulus extends Stimulus {

    public final double satiety;

    public SerotonergicStimulus(SequentialId origin, SequentialId stimulusId, double satiety) {
        super(origin, stimulusId);
        this.satiety = satiety;
    }
}
