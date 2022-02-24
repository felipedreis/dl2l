package br.cefetmg.lsi.l2l.physics;

import br.cefetmg.lsi.l2l.common.Point;
import br.cefetmg.lsi.l2l.common.ResourceLoader;
import br.cefetmg.lsi.l2l.common.SequentialId;
import br.cefetmg.lsi.l2l.world.FruitType;
import br.cefetmg.lsi.l2l.world.WorldObjectType;
import org.newdawn.slick.Image;
import org.newdawn.slick.geom.Circle;
import org.newdawn.slick.geom.Shape;

/**
 * Created by felipe on 07/01/17.
 */
public class ObjectGeometry extends Geometry {
    public final SequentialId id;

    public final Shape shape;
    public final WorldObjectType type;
    private Image texture;
    public final Point point;

    public ObjectGeometry(WorldObjectPositioningAttr attr) {
        super();

        this.point = attr.position;
        this.type = attr.type;

        if (type instanceof FruitType) {
            FruitType fruitType = (FruitType) type;
            this.shape = new Circle((float) point.x, (float) point.y, (float) fruitType.radius);

        } else {
            texture = null;
            shape = null;
        }

        id = attr.id;
    }

    /**
     * Loads the resources of geometry if necessary. It should be called just inside an OpenGL context, otherwise it may crash
     */
    public void load() {
        if(loaded) {
            return;
        }
        super.load();

        if (type instanceof FruitType) {
            FruitType fruitType = (FruitType) type;
            texture = loader.loadImage(fruitType.name().toLowerCase());

        } else {
            texture = null;
        }

        this.loaded = true;
    }

    public Image getTexture() {
        return texture;
    }
}
