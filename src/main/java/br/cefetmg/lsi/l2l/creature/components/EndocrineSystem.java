package br.cefetmg.lsi.l2l.creature.components;

import br.cefetmg.lsi.l2l.common.Constants;
import br.cefetmg.lsi.l2l.common.SequentialId;
import br.cefetmg.lsi.l2l.creature.bd.EndocrineStateLog;
import br.cefetmg.lsi.l2l.stimuli.CortisolStimulus;
import br.cefetmg.lsi.l2l.stimuli.EndocrineState;
import br.cefetmg.lsi.l2l.stimuli.Stimulus;

import java.util.List;

/**
 * HPA-axis slow leaky integrator (adrenal cortex role). Cortisol accumulates from two sources:
 * a circadian morning pulse (fired by {@code PartialAppraisal} on each phase wrap) and a chronic
 * stressor pathway (fired by {@code HomeostaticRegulation} when a drive arousal exceeds
 * {@code STRESS_ACTIVATION_THRESHOLD}). When the cortisol tonic level exceeds
 * {@code CORTISOL_STRESS_THRESHOLD} it activates the STRESS affect, which is an affect (not a
 * drive) and therefore not lethal.
 *
 * <p>Operates on a much slower timescale than {@code NeuromodulatorSystem}: CORTISOL_DECAY ≈ 0.9995
 * gives a half-life of ~1386 cycles, vs. DOPAMINE_DECAY ≈ 0.95 (~14 cycles).
 */
public class EndocrineSystem extends CreatureComponent {

    private double cortisol = 0.0;
    private long publishSeq = 0;

    public EndocrineSystem(SequentialId id) {
        super(id);
    }

    @Override
    public void onReceive(Object message) {
        @SuppressWarnings("unchecked")
        List<Stimulus> stimuli = (List<Stimulus>) message;

        for (Stimulus stimulus : stimuli) {
            if (stimulus instanceof CortisolStimulus cs) {
                onCortisol(cs);
            }
        }

        publishState();
    }

    private void onCortisol(CortisolStimulus cs) {
        cortisol = cortisol * Constants.CORTISOL_DECAY + cs.magnitude;

        double stressLevel = Math.max(0.0,
                (cortisol - Constants.CORTISOL_STRESS_THRESHOLD) * Constants.CORTISOL_STRESS_GAIN);
        double currentStress = creature.emotions().getLevel(Constants.STRESS);
        creature.emotions().regulate(Constants.STRESS, stressLevel - currentStress);
    }

    private void publishState() {
        double stressLevel = creature.emotions().getLevel(Constants.STRESS);
        creature.fullAppraisal().tell(
                new EndocrineState(id, nextStimulusId(), cortisol, stressLevel));
        persist(new EndocrineStateLog(id.key, publishSeq++, cortisol, stressLevel));
    }

    // Package-private accessors for unit tests.
    double cortisol()    { return cortisol; }
}
