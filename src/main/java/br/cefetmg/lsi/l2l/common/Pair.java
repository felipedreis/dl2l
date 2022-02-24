package br.cefetmg.lsi.l2l.common;

/**
 * Created by felipe on 06/01/17.
 */
public class Pair <T,U> {

    public final T first;
    public final U second;


    public Pair(T t, U u){
        first = t;
        second = u;
    }

    @Override
    public String toString() {
        return "Pair[" + first.toString() + ";" + second.toString() + "]";
    }
}
