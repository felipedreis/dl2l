package br.cefetmg.lsi.l2l.creature.components;

import br.cefetmg.lsi.l2l.common.Constants;

import java.io.Serializable;

/**
 * Created by felipe on 20/02/17.
 */
public class Emotion implements Serializable{

    private String name;
    private double level;

    public Emotion(String name) {
        this.name = name;
        this.level = Constants.MIN_AROUSAL_LEVEL;
    }

    public double getLevel() {
        return level;
    }

    public void setLevel(double level) {
        if (level < Constants.MIN_AROUSAL_LEVEL) {
            this.level = Constants.MIN_AROUSAL_LEVEL;
        } else if (level > Constants.MAX_AROUSAL_LEVEL) {
            this.level = Constants.MAX_AROUSAL_LEVEL;
        } else {
            this.level = level;
        }
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Emotion)) return false;

        Emotion emotion = (Emotion) o;

        return name != null ? name.equals(emotion.name) : emotion.name == null;

    }

    @Override
    public int hashCode() {
        return name != null ? name.hashCode() : 0;
    }
}
