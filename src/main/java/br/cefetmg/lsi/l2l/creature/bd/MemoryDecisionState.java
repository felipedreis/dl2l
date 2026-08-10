package br.cefetmg.lsi.l2l.creature.bd;

import br.cefetmg.lsi.l2l.common.SequentialId;
import br.cefetmg.lsi.l2l.world.WorldObjectType;

import java.util.UUID;

/**
 * One consultation of the episodic-memory action filter: how much experience it had to go on, how
 * much of that experience was relevant to the objects in view, and whether it narrowed the choice.
 *
 * <p>This table is the <em>only</em> instrument for memory's influence on behaviour, because
 * {@code chosen_action_state.actionselectiontype} no longer answers the question. Memory now picks
 * an object and hands the surviving actions to the operant table rather than returning a single
 * action, so it rarely ends the filter chain and rarely receives the selection credit — AFFORDANCE
 * usually does. Analyses must read memory's influence rate from here ({@code returned < candidates})
 * and not infer it from the selection type.
 *
 * <p>A row means memory was actually consulted, which is a meaningful denominator:
 * {@code ActionSelection.selectOne} stops as soon as a filter narrows the candidates to one, so an
 * earlier filter deciding on its own means {@code MemoryFilter} never runs and no row is written.
 *
 * <p>{@code decided = false} rows are pass-throughs — memory had no object-level choice to make, or
 * no evidence to make it with. For those {@code returned == candidates}, {@code objectType} and
 * {@code target} are null, and the scores are NaN.
 */
public class MemoryDecisionState implements PersistenceState {

    private final UUID id = UUID.randomUUID();

    private long creatureKey;
    private long seq;
    private long timeMs;
    private long cycle;
    /** Engrams available to score with this cycle. */
    private int engramWindow;
    /** Candidate actions that arrived from the previous filter. */
    private int candidates;
    /** Distinct objects those actions targeted — the denominator for {@code scored}. */
    private int objects;
    /** Candidate objects the engram window had any evidence about. */
    private int scored;
    /** Candidate actions passed on; equal to {@code candidates} on a pass-through. */
    private int returned;
    /** Value of the sampled object; NaN when it was unexplored or nothing was chosen. */
    private double winningScore;
    /** Best value among the objects passed over; NaN when none of them was known. */
    private double runnerUpScore;
    private boolean decided;
    /** The sampled object type, as a name; null on a pass-through. */
    private String objectType;
    private SequentialId target;

    public MemoryDecisionState() { }

    public MemoryDecisionState(long creatureKey, long seq, long timeMs, long cycle,
                               int engramWindow, int candidates, int objects, int scored, int returned,
                               double winningScore, double runnerUpScore, boolean decided,
                               WorldObjectType objectType, SequentialId target) {
        this.creatureKey = creatureKey;
        this.seq = seq;
        this.timeMs = timeMs;
        this.cycle = cycle;
        this.engramWindow = engramWindow;
        this.candidates = candidates;
        this.objects = objects;
        this.scored = scored;
        this.returned = returned;
        this.winningScore = winningScore;
        this.runnerUpScore = runnerUpScore;
        this.decided = decided;
        this.objectType = objectType != null ? objectType.name() : null;
        this.target = target;
    }

    public UUID getId()              { return id; }
    public long getCreatureKey()     { return creatureKey; }
    public long getSeq()             { return seq; }
    public long getTimeMs()          { return timeMs; }
    public long getCycle()           { return cycle; }
    public int getEngramWindow()     { return engramWindow; }
    public int getCandidates()       { return candidates; }
    public int getObjects()          { return objects; }
    public int getScored()           { return scored; }
    public int getReturned()         { return returned; }
    public double getWinningScore()  { return winningScore; }
    public double getRunnerUpScore() { return runnerUpScore; }
    public boolean isDecided()       { return decided; }
    public String getObjectType()    { return objectType; }
    public SequentialId getTarget()  { return target; }
}
