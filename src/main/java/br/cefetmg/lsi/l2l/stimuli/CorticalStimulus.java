package br.cefetmg.lsi.l2l.stimuli;

import br.cefetmg.lsi.l2l.common.SequentialId;
import br.cefetmg.lsi.l2l.creature.common.ActionType;

/**
 * Created by felipe on 18/03/17.
 */
public class CorticalStimulus extends Stimulus {

    public final ActionType action;
    public final SequentialId target;
    public final double angle;
    public final double focus;
    public final double speed;


    public CorticalStimulus(SequentialId origin, SequentialId stimulusId, ActionType action, SequentialId target, double angle, double focus,
                            double speed) {
        super(origin, stimulusId);
        this.action = action;
        this.target = target;
        this.angle = angle;
        this.focus = focus;
        this.speed = speed;
    }
}
