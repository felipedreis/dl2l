package br.cefetmg.lsi.l2l.creature.components;

import br.cefetmg.lsi.l2l.common.Constants;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * Created by felipe on 20/02/17.
 */
public class EmotionalSystemActor implements EmotionalSystem {

    // The four emotions that have both a sympathetic (↑) and a parasympathetic (↓) path.
    // Only these are subject to metabolic drift and count toward arousal / death.
    // Complex affects (stress, apathy) are deferred; declared affects (fear, curiosity, fertility)
    // are disabled — see docs/roadmap/Campos2015_Model_Parity.md §2 and Task 1.
    static final Set<String> ACTIVE = Set.of(
            Constants.HUNGER, Constants.SLEEP, Constants.PAIN, Constants.TEDIUM);

    // Basic drives (bodily needs) are lethal at MAX_AROUSAL; affects (pain, tedium) are evaluative
    // signals that are regulated and steer behaviour but never cause death. See roadmap §2.
    static final Set<String> DRIVES = Set.of(Constants.HUNGER, Constants.SLEEP);

    private List<Emotion> simpleEmotions;

    public EmotionalSystemActor(){
        simpleEmotions = new ArrayList<>();
        // Order must match model_contract.json emotion_index_order (indices 0-8).
        simpleEmotions.add(new Emotion(Constants.HUNGER));    // 0 — active
        simpleEmotions.add(new Emotion(Constants.SLEEP));     // 1 — active
        simpleEmotions.add(new Emotion(Constants.APATHY));    // 2 — deferred (complex affect)
        simpleEmotions.add(new Emotion(Constants.STRESS));    // 3 — deferred (complex affect)
        simpleEmotions.add(new Emotion(Constants.PAIN));      // 4 — active
        simpleEmotions.add(new Emotion(Constants.TEDIUM));    // 5 — active
        simpleEmotions.add(new Emotion(Constants.FEAR));      // 6 — disabled (declared affect)
        simpleEmotions.add(new Emotion(Constants.CURIOSITY)); // 7 — disabled (declared affect)
        simpleEmotions.add(new Emotion(Constants.FERTILITY)); // 8 — disabled (declared affect)
    }

    @Override
    public void regulateAll(double delta) {
        simpleEmotions.stream()
                .filter(e -> ACTIVE.contains(e.getName()))
                .forEach(e -> e.setLevel(e.getLevel() + delta));
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
                .filter(e -> ACTIVE.contains(e.getName()))
                .max(Comparator.comparing(Emotion::getLevel))
                .get();
    }

    @Override
    public Emotion getMaxDriveArousal() {
        return simpleEmotions.stream()
                .filter(e -> DRIVES.contains(e.getName()))
                .max(Comparator.comparing(Emotion::getLevel))
                .get();
    }
}
