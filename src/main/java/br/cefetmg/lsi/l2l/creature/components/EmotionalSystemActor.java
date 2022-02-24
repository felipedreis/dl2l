package br.cefetmg.lsi.l2l.creature.components;

import br.cefetmg.lsi.l2l.common.Constants;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Created by felipe on 20/02/17.
 */
public class EmotionalSystemActor implements EmotionalSystem {

    private List<Emotion> simpleEmotions;

    public EmotionalSystemActor(){
        simpleEmotions = new ArrayList<>();
        simpleEmotions.add(new Emotion(Constants.HUNGER));
        simpleEmotions.add(new Emotion(Constants.SLEEP));
    }

    @Override
    public void regulateAll(double delta) {
        simpleEmotions.forEach(emotion -> emotion.setLevel(emotion.getLevel() + delta));
    }

    @Override
    public Emotion regulate(String emotion, double delta) {
        Emotion regulated = simpleEmotions.stream()
                .filter(e -> e.getName().equals(emotion))
                .findFirst()
                .get();
        regulated.setLevel(regulated.getLevel() + delta);
        return regulated;
    }

    @Override
    public double getLevel(String emotion) {
        return simpleEmotions.stream()
                .filter(e -> e.getName().equals(emotion))
                .findFirst()
                .get().getLevel();
    }

    @Override
    public Emotion getMaxArousal() {
        return simpleEmotions.stream()
                .max(Comparator.comparing(Emotion::getLevel))
                .get();
    }

    @Override
    public Emotion getMaxComplexArousal() {
        throw new IllegalStateException("not implemented yet");
    }
}
