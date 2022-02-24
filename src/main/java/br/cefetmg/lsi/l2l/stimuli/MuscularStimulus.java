package br.cefetmg.lsi.l2l.stimuli;

import br.cefetmg.lsi.l2l.common.SequentialId;

/**
 * Created by felipe on 03/01/17.
 */
public class MuscularStimulus extends Stimulus{
    public final double speed;
    public final double angle;

    public MuscularStimulus(SequentialId origin, SequentialId stimulusId, double speed, double direction) {
        super(origin, stimulusId);
        this.speed = speed;
        this.angle = direction;
    }

}
