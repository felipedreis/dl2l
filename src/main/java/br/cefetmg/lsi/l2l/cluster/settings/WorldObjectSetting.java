package br.cefetmg.lsi.l2l.cluster.settings;

import br.cefetmg.lsi.l2l.world.WorldObjectType;

/**
 * Created by felipe on 03/04/17.
 */
public class WorldObjectSetting {

    private WorldObjectType type;
    private int quantity;

    public WorldObjectSetting() {
    }

    public WorldObjectType getType() {
        return type;
    }

    public void setType(WorldObjectType type) {
        this.type = type;
    }

    public int getQuantity() {
        return quantity;
    }

    public void setQuantity(int quantity) {
        this.quantity = quantity;
    }
}
