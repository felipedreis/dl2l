package br.cefetmg.lsi.l2l.creature.actionSelector;

import br.cefetmg.lsi.l2l.creature.components.Emotion;

/**
 * The inputs of one action-selection cycle, handed to an {@link ActionSelector} as an immutable value.
 *
 * <p>The filter chain reads only {@code toRegulate}. The arbiter also reads the drive levels {@code h},
 * the {@code focalDrive} it keys its cells by, and the behavioural efficiency (its budget, #114).
 *
 * @param toRegulate  the emotion the pre-filters regulate this cycle
 * @param h           drive levels in {@code FocusTracker.DRIVES} order; copied on the way in and out.
 *                    {@code null} when the focal drive tracker is off (no levels are computed)
 * @param focalDrive  the focal drive, or {@code null} when no episode is open or the flag is off
 * @param efficiency  behavioural efficiency of the creature this cycle
 */
public record SelectionContext(Emotion toRegulate, double[] h, String focalDrive, double efficiency) {

    public SelectionContext {
        h = h == null ? null : h.clone();
    }

    @Override
    public double[] h() {
        return h == null ? null : h.clone();
    }
}
