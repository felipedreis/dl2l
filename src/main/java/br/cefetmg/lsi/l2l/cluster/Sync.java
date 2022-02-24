package br.cefetmg.lsi.l2l.cluster;

import akka.actor.ActorRef;
import akka.pattern.Patterns;
import akka.util.Timeout;
import scala.concurrent.Await;
import scala.concurrent.Future;
import scala.concurrent.duration.Duration;

/**
 * Created by felipe on 07/04/17.
 */
public class Sync {


    public static  <R, T> R ask(ActorRef actorRef, T message, int seconds) {

        Timeout timeout = Timeout.apply(Duration.create(seconds, "second"));
        Future<Object> future = Patterns.ask(actorRef, message, timeout);

        try {
            return (R) Await.result(future, timeout.duration());

        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
