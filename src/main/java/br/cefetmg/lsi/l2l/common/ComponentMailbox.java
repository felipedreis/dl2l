package br.cefetmg.lsi.l2l.common;

import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.dispatch.MailboxType;
import akka.dispatch.MessageQueue;
import com.typesafe.config.Config;
import scala.Option;

/**
 * Created by felipe on 03/01/17.
 */
public class ComponentMailbox implements MailboxType {

    /**
     * Optional per-dispatcher cap on {@link ComponentMessageQueue}'s batch size - Akka
     * instantiates one {@link MailboxType} per dispatcher that references this class, passing
     * that dispatcher's own config subtree, so e.g. {@code bd-dispatcher { max-batch-size = 500 }}
     * bounds only BDActor's mailbox while {@code component-dispatcher} (no such key) stays
     * unbounded. See docs/plans/issue-77-bdactor-oom-fix.md.
     */
    private final int maxBatchSize;

    /**
     * Optional, independent per-dispatcher cap on total states (not envelopes) - see
     * {@link ComponentMessageQueue#ComponentMessageQueue(int, int)}'s javadoc for why this
     * exists alongside {@link #maxBatchSize} rather than replacing it. See
     * docs/plans/issue-77-bdactor-oom-fix-followup.md.
     */
    private final int maxStatesPerBatch;

    public ComponentMailbox(ActorSystem.Settings settings, Config config) {
        this.maxBatchSize = config.hasPath("max-batch-size")
                ? config.getInt("max-batch-size")
                : Integer.MAX_VALUE;
        this.maxStatesPerBatch = config.hasPath("max-states-per-batch")
                ? config.getInt("max-states-per-batch")
                : Integer.MAX_VALUE;
    }

    public MessageQueue create(Option<ActorRef> option, Option<ActorSystem> option1) {
        return new ComponentMessageQueue(maxBatchSize, maxStatesPerBatch);
    }
}
