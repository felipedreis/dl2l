package br.cefetmg.lsi.l2l.physics;

import br.cefetmg.lsi.l2l.common.Constants;
import br.cefetmg.lsi.l2l.common.ResourceLoader;
import br.cefetmg.lsi.l2l.common.SequentialId;
import org.newdawn.slick.Image;
import org.newdawn.slick.geom.Circle;

import java.io.Serializable;

/**
 * Created by felipe on 07/01/17.
 */
public class CreatureGeometry extends Geometry implements Serializable {
    public final SequentialId id;

    public final Circle body;
    public final Circle olfactoryField;
    public final Arc mouth;
    public final Arc visionField;

    public Image bodyTexture;
    public Image mouthTexture;

    public CreatureGeometry(CreaturePositioningAttr attr) {
        body = new Circle((float) attr.position.x, (float) attr.position.y,
                (float) attr.bodyRadius);

        olfactoryField = new Circle((float) attr.position.x,
                (float) attr.position.y, (float) attr.olfactoryFieldRadius);

        visionField = new Arc((float) attr.position.x, (float) attr.position.y,
                (float) Constants.DEFAULT_VISION_FIELD_RADIUS,
                (float) attr.visionFieldPosition,
                (float) attr.visionFieldOpening, 10);

        mouth = new Arc((float) attr.position.x, (float) attr.position.y,
                (float) Constants.DEFAULT_MOUTH_RADIUS,
                (float) attr.visionFieldPosition,
                (float) Constants.DEFAULT_MOUTH_OPENING, 10);
        this.id = attr.bodyId;
    }


    @Override
    public void load() {
        if(loaded) {
            return;
        }

        super.load();

        bodyTexture = loader.loadImage("body");
        mouthTexture = loader.loadImage("mouth");
        loaded = true;
    }
}
