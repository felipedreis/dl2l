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

    /**
     * Fires the same ask to every actor in {@code actorRefs} concurrently and waits for all
     * replies - unlike calling {@link #ask} in a loop, the {@code seconds} timeout applies to
     * the whole batch, not per-actor sequentially. Used to drain every {@code BDActor} shard
     * (see PersistenceExtension) in parallel rather than one at a time.
     */
    public static <T> void askAll(ActorRef[] actorRefs, T message, int seconds) {
        Timeout timeout = Timeout.apply(Duration.create(seconds, "second"));
        Future<Object>[] futures = new Future[actorRefs.length];
        for (int i = 0; i < actorRefs.length; i++) {
            futures[i] = Patterns.ask(actorRefs[i], message, timeout);
        }
        try {
            for (Future<Object> future : futures) {
                Await.result(future, timeout.duration());
            }
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
