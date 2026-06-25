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
        // Order must match model_contract.json emotion_index_order (indices 0-8).
        simpleEmotions.add(new Emotion(Constants.HUNGER));    // 0 — live
        simpleEmotions.add(new Emotion(Constants.SLEEP));     // 1 — live
        simpleEmotions.add(new Emotion(Constants.APATHY));    // 2 — placeholder
        simpleEmotions.add(new Emotion(Constants.STRESS));    // 3 — placeholder
        simpleEmotions.add(new Emotion(Constants.PAIN));      // 4 — placeholder
        simpleEmotions.add(new Emotion(Constants.TEDIUM));    // 5 — placeholder
        simpleEmotions.add(new Emotion(Constants.FEAR));      // 6 — placeholder
        simpleEmotions.add(new Emotion(Constants.CURIOSITY)); // 7 — placeholder
        simpleEmotions.add(new Emotion(Constants.FERTILITY)); // 8 — placeholder
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
        return simpleEmotions.stream()
                .filter(e -> !e.getName().equals(Constants.HUNGER) && !e.getName().equals(Constants.SLEEP))
                .max(Comparator.comparing(Emotion::getLevel))
                .orElseGet(this::getMaxArousal);
    }
}
