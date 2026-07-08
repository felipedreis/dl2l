package br.cefetmg.lsi.l2l.creature.components;

import java.util.List;

/**
 * Created by felipe on 20/02/17.
 */
public interface EmotionalSystem {

    void regulateAll(double delta);
    Emotion regulate(String emotion, double delta);
    double getLevel(String emotion);

    /** Max arousal over all active emotions (drives + affects) — the dominant emotion for action selection. */
    Emotion getMaxArousal();

    /**
     * Max arousal over the basic <em>drives</em> only (hunger, sleep). Affects (pain, tedium) are
     * evaluative signals, not lethal deficits, so only drives count toward the death threshold.
     */
    Emotion getMaxDriveArousal();

}
