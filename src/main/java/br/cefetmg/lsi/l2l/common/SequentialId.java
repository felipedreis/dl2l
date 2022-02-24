package br.cefetmg.lsi.l2l.common;

import javax.persistence.Embeddable;
import java.io.Serializable;

/**
 * Created by felipe on 02/01/17.
 */
@Embeddable
public class SequentialId implements Serializable {

    public final long key;
    public final long sequential;

    public SequentialId(){
        key = 0;
        sequential = 0;
    }

    public SequentialId(long key) {
        this.key = key;
        this.sequential = 0;
    }

    private SequentialId(long key, long sequential) {
        this.key = key;
        this.sequential = sequential;
    }

    public SequentialId next() {
        return new SequentialId(key, sequential + 1);
    }

    public SequentialId father() {
        return new SequentialId(key, 0);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SequentialId)) return false;

        SequentialId that = (SequentialId) o;

        if (key != that.key) return false;
        return sequential == that.sequential;

    }

    @Override
    public int hashCode() {
        int result = (int) (key ^ (key >>> 32));
        result = 31 * result + (int) (sequential ^ (sequential >>> 32));
        return result;
    }

    @Override
    public String toString() {
        return key + ":" + sequential;
    }

}
