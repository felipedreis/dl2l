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

    public ComponentMailbox(ActorSystem.Settings settings, Config config) {
    }

    public MessageQueue create(Option<ActorRef> option, Option<ActorSystem> option1) {
        return new ComponentMessageQueue();
    }
}
