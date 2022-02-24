package br.cefetmg.lsi.l2l.world;

/**
 * Created by felipe on 24/08/17.
 */
public class Self implements WorldObjectType {
    public static Self get() {
        return new Self();
    }

    @Override
    public String name() {
        return "self";
    }
}
