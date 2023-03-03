package br.cefetmg.lsi.l2l.physics;

import br.cefetmg.lsi.l2l.common.Point;
import br.cefetmg.lsi.l2l.common.SequentialId;
import br.cefetmg.lsi.l2l.world.FruitType;
import br.cefetmg.lsi.l2l.world.WorldObjectType;

import java.io.Serializable;
import java.util.List;

/**
 * Created by felipe on 06/01/17.
 */
public class WorldObjectPositioningAttr implements Serializable{

    public final SequentialId id;
    public final Point position;
    public final WorldObjectType type;

    public WorldObjectPositioningAttr(SequentialId id, Point position, WorldObjectType type){
        this.id = id;
        this.position = position;
        this.type = type;
    }

}
