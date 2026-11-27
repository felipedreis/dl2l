package br.cefetmg.lsi.l2l.creature.bd;

import br.cefetmg.lsi.l2l.creature.arbiter.Arbiter;
import br.cefetmg.lsi.l2l.creature.arbiter.ArbitrationDecision;
import br.cefetmg.lsi.l2l.creature.arbiter.Estimate;
import br.cefetmg.lsi.l2l.creature.arbiter.ExecutedEstimate;
import br.cefetmg.lsi.l2l.creature.common.Action;
import br.cefetmg.lsi.l2l.creature.common.Perception;

import java.util.Arrays;
import java.util.UUID;

/**
 * Issue #105: one arbiter decision, flattened for the {@code arbitration_decision_state} table (uncertainty
 * arbitration paper, App. A step 9). Written by {@code FullAppraisal} in the same batch as the cycle's
 * {@link ChosenActionState}; rows are immutable once written.
 *
 * <p>Every number comes from the decision already in hand (no extra estimate call), so the row costs nothing
 * the arbiter did not already compute. The pick is the pre-gate choice; {@code executed*} is what the creature
 * did after the hysteresis gate, with the estimates the calibration needs. Each controller's columns are
 * {@code NaN} when that controller was not consulted (or, for {@code *_q}, did not see the executed action),
 * and the table maps {@code NaN} to null: an absent value is never a number.
 *
 * <p>Built by {@link #of}, so the mapping is tested once without an actor. The per-controller values are kept
 * in arrays indexed by {@link Arbiter.ControllerType#ordinal()}, so the schema needs only the enum.
 */
public class ArbitrationDecisionState implements PersistenceState {

    private final UUID id = UUID.randomUUID();

    private long creatureKey;
    private long seq;
    private long timeMs;
    private long cycle;
    private String focalDrive;
    private double efficiency;
    private String affordableController;
    private int candidateCount;
    private String decidingController;
    private int nConsulted;
    private String source;
    private int nTiedAtMax;
    /** The novelty bonus of the chosen candidate (#114); 0 when the deciding controller has no neighbour count. */
    private double noveltyBonus;

    private String chosenAction;
    private String chosenObjectType;
    private double chosenQ;
    private double chosenSe;
    private double chosenSigma;
    private double bestQ;
    private double bestSe;
    private double bestSigma;
    /** {@code NaN} when there is one candidate. */
    private double runnerUpQ = Double.NaN;
    private double runnerUpSe = Double.NaN;
    private double runnerUpSigma = Double.NaN;

    private String executedAction;
    private String executedObjectType;
    private String executedSource;
    private double executedQ;
    private double executedSe;
    private double executedSigma;

    /** Plan A.3: the drive levels at decision time in {@code FocusTracker.DRIVES} order; NaN when not supplied. */
    private final double[] h = nans(4);
    /** Plan A.3: the executed action's target key; null when the action has no perception id. */
    private Long executedTargetKey;

    private final double[] conf = unset();
    private final double[] controllerQ = unset();
    private final double[] controllerSe = unset();
    private final double[] controllerSigma = unset();
    /** Per controller, the {@link Estimate.Basis} of the estimate of the executed candidate; null when the controller was not consulted. */
    private final String[] controllerBasis = new String[Arbiter.ControllerType.values().length];

    public ArbitrationDecisionState() { }

    /**
     * @param creatureKey creature key, as every per-creature table
     * @param seq          per-creature decision counter, from 0
     * @param timeMs       wall clock at persist
     * @param cycle        the decision cycle ({@code DecisionRecord.cycle}), the join key of {@code episode_return}
     * @param focalDrive   the focal drive of the decision, {@code null} when no episode is open
     * @param efficiency   the behavioural efficiency the arbiter was given
     * @param d            the decision
     * @param x            the executed action's estimates, after the gate; not null
     */
    public static ArbitrationDecisionState of(long creatureKey, long seq, long timeMs, long cycle, String focalDrive,
                                              double efficiency, ArbitrationDecision d, ExecutedEstimate x) {
        return of(creatureKey, seq, timeMs, cycle, focalDrive, efficiency, d, x, null);
    }

    /**
     * As {@link #of(long, long, long, long, String, double, ArbitrationDecision, ExecutedEstimate)}, plus the drive
     * levels at decision time ({@code h}, four entries in {@code FocusTracker.DRIVES} order, or null for none).
     */
    public static ArbitrationDecisionState of(long creatureKey, long seq, long timeMs, long cycle, String focalDrive,
                                              double efficiency, ArbitrationDecision d, ExecutedEstimate x,
                                              double[] h) {
        if (d == null || x == null) {
            throw new IllegalArgumentException("decision and executed estimate must not be null");
        }
        if (h != null && h.length != 4) {
            throw new IllegalArgumentException("h must have four drive levels");
        }
        ArbitrationDecisionState s = new ArbitrationDecisionState();
        s.creatureKey = creatureKey;
        s.seq = seq;
        s.timeMs = timeMs;
        s.cycle = cycle;
        s.focalDrive = focalDrive;
        s.efficiency = efficiency;
        s.affordableController = d.mostExpensiveAffordable().name();
        s.candidateCount = d.candidateCount();
        s.decidingController = d.decidingController().name();
        s.nConsulted = d.consultations().size();
        s.source = d.source().name();
        s.nTiedAtMax = d.nTiedAtMax();
        s.noveltyBonus = d.noveltyBonus();

        s.chosenAction = d.chosen().type.name();
        s.chosenObjectType = objectTypeName(d.chosen());
        s.chosenQ = d.chosenEstimate().q();
        s.chosenSe = d.chosenEstimate().se();
        s.chosenSigma = d.chosenEstimate().sigma();
        s.bestQ = d.bestEstimate().q();
        s.bestSe = d.bestEstimate().se();
        s.bestSigma = d.bestEstimate().sigma();
        if (d.runnerUpEstimate() != null) {
            s.runnerUpQ = d.runnerUpEstimate().q();
            s.runnerUpSe = d.runnerUpEstimate().se();
            s.runnerUpSigma = d.runnerUpEstimate().sigma();
        }

        s.executedAction = x.action().type.name();
        s.executedObjectType = objectTypeName(x.action());
        s.executedSource = x.source().name();
        s.executedQ = x.deciding().q();
        s.executedSe = x.deciding().se();
        s.executedSigma = x.deciding().sigma();
        Perception executedPerception = x.action().perception;
        s.executedTargetKey = executedPerception != null && executedPerception.id != null
                ? executedPerception.id.key : null;
        if (h != null) {
            System.arraycopy(h, 0, s.h, 0, h.length);
        }

        for (ArbitrationDecision.Consultation c : d.consultations()) {
            s.conf[c.controller().ordinal()] = c.conf();
        }
        x.byController().forEach((type, e) -> {
            s.controllerQ[type.ordinal()] = e.q();
            s.controllerSe[type.ordinal()] = e.se();
            s.controllerSigma[type.ordinal()] = e.sigma();
            s.controllerBasis[type.ordinal()] = e.basis().name();
        });
        return s;
    }

    private static double[] unset() {
        return nans(Arbiter.ControllerType.values().length);
    }

    private static double[] nans(int n) {
        double[] a = new double[n];
        Arrays.fill(a, Double.NaN);
        return a;
    }

    /** The object type of an action, by the same rule as {@code Valuation}'s episode rows: null when absent. */
    private static String objectTypeName(Action a) {
        return a.getTarget() != null ? a.getTarget().name() : null;
    }

    public UUID getId() { return id; }
    public long getCreatureKey() { return creatureKey; }
    public long getSeq() { return seq; }
    public long getTimeMs() { return timeMs; }
    public long getCycle() { return cycle; }
    public String getFocalDrive() { return focalDrive; }
    public double getEfficiency() { return efficiency; }
    public String getAffordableController() { return affordableController; }
    public int getCandidateCount() { return candidateCount; }
    public String getDecidingController() { return decidingController; }
    public int getNConsulted() { return nConsulted; }
    public String getSource() { return source; }
    public int getNTiedAtMax() { return nTiedAtMax; }
    public double getNoveltyBonus() { return noveltyBonus; }
    public String getChosenAction() { return chosenAction; }
    public String getChosenObjectType() { return chosenObjectType; }
    public double getChosenQ() { return chosenQ; }
    public double getChosenSe() { return chosenSe; }
    public double getChosenSigma() { return chosenSigma; }
    public double getBestQ() { return bestQ; }
    public double getBestSe() { return bestSe; }
    public double getBestSigma() { return bestSigma; }
    public double getRunnerUpQ() { return runnerUpQ; }
    public double getRunnerUpSe() { return runnerUpSe; }
    public double getRunnerUpSigma() { return runnerUpSigma; }
    public String getExecutedAction() { return executedAction; }
    public String getExecutedObjectType() { return executedObjectType; }
    public String getExecutedSource() { return executedSource; }
    public double getExecutedQ() { return executedQ; }
    public double getExecutedSe() { return executedSe; }
    public double getExecutedSigma() { return executedSigma; }
    public Long getExecutedTargetKey() { return executedTargetKey; }
    public double getH(int i) { return h[i]; }

    public double getConf(Arbiter.ControllerType c) { return conf[c.ordinal()]; }
    public double getControllerQ(Arbiter.ControllerType c) { return controllerQ[c.ordinal()]; }
    public double getControllerSe(Arbiter.ControllerType c) { return controllerSe[c.ordinal()]; }
    public double getControllerSigma(Arbiter.ControllerType c) { return controllerSigma[c.ordinal()]; }
    public String getControllerBasis(Arbiter.ControllerType c) { return controllerBasis[c.ordinal()]; }
}
