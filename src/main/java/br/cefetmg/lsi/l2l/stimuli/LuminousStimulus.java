package br.cefetmg.lsi.l2l.stimuli;

import br.cefetmg.lsi.l2l.common.Point;
import br.cefetmg.lsi.l2l.common.SequentialId;
import br.cefetmg.lsi.l2l.world.WorldObjectType;

/**
 * Created by felipe on 02/01/17.
 */
public class LuminousStimulus extends Stimulus {

    private Point point;

    private WorldObjectType objectType;

    public LuminousStimulus(SequentialId origin, SequentialId stimulusId, WorldObjectType objectType, Point point) {
        super(origin, stimulusId);
        this.point = point;
        this.objectType = objectType;
    }

    public Point getPoint() {
        return point;
    }

    public void setPoint(Point point) {
        this.point = point;
    }

    public WorldObjectType getObjectType() {
        return objectType;
    }

    public void setObjectType(WorldObjectType objectType) {
        this.objectType = objectType;
    }
}
