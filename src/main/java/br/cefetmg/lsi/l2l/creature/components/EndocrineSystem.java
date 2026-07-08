package br.cefetmg.lsi.l2l.creature.components;

import br.cefetmg.lsi.l2l.common.Constants;
import br.cefetmg.lsi.l2l.common.SequentialId;
import br.cefetmg.lsi.l2l.creature.bd.EndocrineStateLog;
import br.cefetmg.lsi.l2l.stimuli.CortisolStimulus;
import br.cefetmg.lsi.l2l.stimuli.EndocrineTick;
import br.cefetmg.lsi.l2l.stimuli.EndocrineState;
import br.cefetmg.lsi.l2l.stimuli.Stimulus;

import java.util.List;

/**
 * HPA-axis slow leaky integrator (adrenal cortex role). Cortisol is driven by two sources:
 *
 * <ol>
 *   <li><b>EndocrineTick</b> (every cycle, from {@code PartialAppraisal}): applies passive
 *       adrenal clearance ({@code cortisol *= DECAY}) then adds circadian-modulated synthesis
 *       with saturating negative feedback: {@code synth / (1 + k·cortisol)}. This means the
 *       resting tonic settles at a low, bounded level (≈0.82 at baseline, ≈2.1 at the morning
 *       peak) — well below the stress-activation threshold of 3.0.</li>
 *   <li><b>CortisolStimulus</b> (from {@code HomeostaticRegulation}, sustained stressors only):
 *       adds {@code magnitude / (1 + k·cortisol)} — the same saturating feedback limits runaway
 *       even under chronic load. HomeostaticRegulation only emits after a drive is held above
 *       {@code STRESS_ACTIVATION_THRESHOLD} for {@code CORTISOL_STRESSOR_SUSTAIN_TICKS}
 *       consecutive cycles (sustained-deprivation gate).</li>
 * </ol>
 *
 * <p>When cortisol exceeds {@code CORTISOL_STRESS_THRESHOLD} the STRESS affect activates (not
 * lethal, unlike the basic drives).
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
            if (stimulus instanceof EndocrineTick et) {
                onTick(et);
            } else if (stimulus instanceof CortisolStimulus cs) {
                onCortisol(cs);
            }
        }

        updateStress();
        publishState();
    }

    private void onTick(EndocrineTick et) {
        cortisol *= Constants.CORTISOL_DECAY;
        double phase = et.circadianPhase;
        double synth = Constants.CORTISOL_CIRCADIAN_BASELINE
                + Constants.CORTISOL_CIRCADIAN_AMPLITUDE
                  * Math.max(0.0, Math.sin(phase - Constants.CORTISOL_MORNING_OFFSET));
        cortisol += synth / (1.0 + Constants.CORTISOL_FEEDBACK_GAIN * cortisol);
    }

    private void onCortisol(CortisolStimulus cs) {
        cortisol += cs.magnitude / (1.0 + Constants.CORTISOL_FEEDBACK_GAIN * cortisol);
    }

    private void updateStress() {
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
    double cortisol() { return cortisol; }
}
