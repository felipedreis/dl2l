package br.cefetmg.lsi.l2l.creature.bd;

import br.cefetmg.lsi.l2l.common.SequentialId;
import br.cefetmg.lsi.l2l.creature.common.ActionType;

import java.util.UUID;

/**
 * One consultation of the episodic-memory action filter: how much experience it had to go on,
 * how much of that experience was relevant to the candidate actions, and how decisive the result
 * was.
 *
 * <p>{@code chosen_action_state.actionselectiontype = MEMORY} already tells us memory <em>won</em>
 * a decision, but not whether it won on a broad, confident margin or on one weak engram. That
 * distinction is what separates "the mechanism is wired up" from "the mechanism is working", so
 * it gets its own record.
 *
 * <p>A row means memory was actually consulted, which is a meaningful denominator:
 * {@code ActionSelection.selectOne} stops as soon as a filter narrows the candidates to one, so
 * an earlier filter deciding on its own means {@code MemoryFilter} never runs and no row is
 * written. {@code decided = false} rows are consultations where memory had no opinion and passed
 * the candidates through to the next filter; for those, {@code action}/{@code target} are null and
 * the scores are NaN.
 */
public class MemoryDecisionState implements PersistenceState {

    private final UUID id = UUID.randomUUID();

    private long creatureKey;
    private long seq;
    private long timeMs;
    private long cycle;
    private int engramWindow;
    private int candidates;
    private int scored;
    private double winningScore;
    private double runnerUpScore;
    private boolean decided;
    private ActionType action;
    private SequentialId target;

    public MemoryDecisionState() { }

    public MemoryDecisionState(long creatureKey, long seq, long timeMs, long cycle,
                               int engramWindow, int candidates, int scored,
                               double winningScore, double runnerUpScore, boolean decided,
                               ActionType action, SequentialId target) {
        this.creatureKey = creatureKey;
        this.seq = seq;
        this.timeMs = timeMs;
        this.cycle = cycle;
        this.engramWindow = engramWindow;
        this.candidates = candidates;
        this.scored = scored;
        this.winningScore = winningScore;
        this.runnerUpScore = runnerUpScore;
        this.decided = decided;
        this.action = action;
        this.target = target;
    }

    public UUID getId()             { return id; }
    public long getCreatureKey()    { return creatureKey; }
    public long getSeq()            { return seq; }
    public long getTimeMs()         { return timeMs; }
    public long getCycle()          { return cycle; }
    public int getEngramWindow()    { return engramWindow; }
    public int getCandidates()      { return candidates; }
    public int getScored()          { return scored; }
    public double getWinningScore() { return winningScore; }
    public double getRunnerUpScore() { return runnerUpScore; }
    public boolean isDecided()      { return decided; }
    public ActionType getAction()   { return action; }
    public SequentialId getTarget() { return target; }
}
