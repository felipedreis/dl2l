package br.cefetmg.lsi.l2l.stimuli;

import br.cefetmg.lsi.l2l.common.Point;
import br.cefetmg.lsi.l2l.common.SequentialId;
import br.cefetmg.lsi.l2l.world.WorldObjectType;

/**
 * Created by felipe on 24/01/17.
 */
public class SmellStimulus extends Stimulus {
    public final WorldObjectType objectType;
    public final Point point;
    public SmellStimulus(SequentialId origin, SequentialId stimulusId, WorldObjectType objectType, Point point) {
        super(origin, stimulusId);
        this.objectType = objectType;
        this.point = point;
    }
}
