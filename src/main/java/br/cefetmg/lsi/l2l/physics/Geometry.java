package br.cefetmg.lsi.l2l.physics;

import br.cefetmg.lsi.l2l.common.ResourceLoader;

/**
 * Created by felipe on 20/02/17.
 */
public abstract class Geometry {
    protected boolean loaded;
    protected ResourceLoader loader;

    public Geometry(){
        loader = null;
        loaded = false;
    }

    public boolean isLoaded() {
        return loaded;
    }

    public void setLoaded(boolean loaded) {
        this.loaded = loaded;
    }

    public void load() {
        if (loader == null) {
            try {
                loader = new ResourceLoader();
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        }
    }
}
