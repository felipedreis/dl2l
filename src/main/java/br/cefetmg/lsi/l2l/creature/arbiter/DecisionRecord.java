package br.cefetmg.lsi.l2l.creature.arbiter;

import br.cefetmg.lsi.l2l.creature.common.Action;

/**
 * One decision of the arbiter, sent from {@code FullAppraisal} to {@code Valuation} each cycle so the
 * episode buffer can hold it. Immutable: the drive levels are copied on the way in and out.
 *
 * @param cycle      decision cycle, on the same epoch as {@code MemorySystem.currentDecisionCycle()}
 * @param action     the action that was executed (after the hysteresis gate)
 * @param h          drive levels at decision time, in {@link FocusTracker#DRIVES} order
 * @param focalDrive focal drive, or {@code null} when no episode was open
 * @param chosenQ    the arbiter's Q of the chosen candidate; {@code NaN} until #104
 */
public record DecisionRecord(int cycle, Action action, double[] h, String focalDrive, double chosenQ) {

    public DecisionRecord {
        if (action == null || h == null) {
            throw new IllegalArgumentException("action and h must not be null");
        }
        h = h.clone();
    }

    @Override
    public double[] h() {
        return h.clone();
    }
}
