package br.cefetmg.lsi.l2l.stimuli;

import br.cefetmg.lsi.l2l.common.SequentialId;

/**
 * Issue #85: the creature's wall-clock pacemaker beat, sent by {@code CreatureActor.tick()}
 * to {@code PartialAppraisal}. Receiving one is what makes a cognitive cycle run: the
 * component drains its perception buffer and appraises whatever accumulated since the
 * previous tick. Perception arriving on its own only fills that buffer.
 *
 * <p>This replaces the empty {@code String} the heartbeat used to send. That string was
 * silently discarded by {@link br.cefetmg.lsi.l2l.common.ComponentMessageQueue} and served
 * only to force a mailbox run, which meant a heartbeat cycle and a perception-driven cycle
 * were indistinguishable in kind - the direct cause of the ~66 Hz see/don't-see alternation
 * measured in issue #85.
 *
 * <p>Extends {@link Stimulus} deliberately: {@code ComponentMessageQueue.dequeue()} merges
 * {@code Stimulus} instances into one batch, so a tick coalesces with any perception queued
 * alongside it and costs one {@code onReceive} per tick. Any other message type would be
 * delivered as its own isolated single-element batch instead.
 *
 * <p>Nothing reads {@code stimulusId}; the tick is never recorded as a received stimulus in
 * the persisted change record.
 */
public class CognitiveTick extends Stimulus {

    public CognitiveTick(SequentialId origin, SequentialId stimulusId) {
        super(origin, stimulusId);
    }
}
