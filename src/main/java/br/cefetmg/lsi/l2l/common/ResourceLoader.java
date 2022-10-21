package br.cefetmg.lsi.l2l.common;


import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Created by felipe on 07/01/17.
 * @deprecated
 */
public class ResourceLoader {

    private static Map<String, Object> resources = new ConcurrentHashMap<>();

    public ResourceLoader() {
        /*
        try {
            GLContext.loadOpenGLLibrary();
        } catch (LWJGLException e) {
            e.printStackTrace();
        }*/
    }

    /*public Image loadImage(String name) {
        if(resources.containsKey(name))
            return (Image) resources.get(name);

        Image image = null;

        try {
            image = new Image("images/" + name + ".png");

        } catch (SlickException e) {
            throw new RuntimeException("Cannot load the requested resource " + name, e);
        }
        resources.put(name, image);
        return image;
    }*/
}
