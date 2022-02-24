package br.cefetmg.lsi.l2l.creature.components;

import java.util.List;

/**
 * Created by felipe on 20/02/17.
 */
public interface EmotionalSystem {

    void regulateAll(double delta);
    Emotion regulate(String emotion, double delta);
    double getLevel(String emotion);

    Emotion getMaxArousal();
    Emotion getMaxComplexArousal();

}
