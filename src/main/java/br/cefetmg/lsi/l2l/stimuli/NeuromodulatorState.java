package br.cefetmg.lsi.l2l.stimuli;

import br.cefetmg.lsi.l2l.common.SequentialId;

/**
 * Published tonic neuromodulator concentrations broadcast by
 * {@link br.cefetmg.lsi.l2l.creature.components.NeuromodulatorSystem} after each update. Consumers
 * (e.g. {@code FullAppraisal}) cache the latest values and use them as slow-varying behavioural
 * gains, rather than querying the pool synchronously — the tonic field is diffuse and
 * eventually-consistent by nature.
 */
public class NeuromodulatorState extends Stimulus {

    public final double dopamineTonic;
    public final double serotoninTonic;

    public NeuromodulatorState(SequentialId origin, SequentialId stimulusId,
                               double dopamineTonic, double serotoninTonic) {
        super(origin, stimulusId);
        this.dopamineTonic = dopamineTonic;
        this.serotoninTonic = serotoninTonic;
    }
}
