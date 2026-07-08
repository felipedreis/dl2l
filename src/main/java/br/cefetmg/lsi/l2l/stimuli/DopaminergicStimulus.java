package br.cefetmg.lsi.l2l.stimuli;

import br.cefetmg.lsi.l2l.common.SequentialId;
import br.cefetmg.lsi.l2l.creature.common.ActionType;
import br.cefetmg.lsi.l2l.world.WorldObjectType;

/**
 * A phasic dopamine release event: a discrete quantum of reward-prediction error emitted by
 * {@code Valuation} (the VTA/SNc role) at the moment an outcome is evaluated. It is delivered as
 * an explicit message to {@link br.cefetmg.lsi.l2l.creature.components.NeuromodulatorSystem},
 * which integrates it into the tonic dopamine concentration — the molecule *is* the message.
 *
 * <p>{@code rpe = reward - expected}; positive means the outcome beat expectation.
 */
public class DopaminergicStimulus extends Stimulus {

    public final double rpe;
    public final WorldObjectType target;
    public final ActionType action;

    public DopaminergicStimulus(SequentialId origin, SequentialId stimulusId,
                                double rpe, WorldObjectType target, ActionType action) {
        super(origin, stimulusId);
        this.rpe = rpe;
        this.target = target;
        this.action = action;
    }
}
