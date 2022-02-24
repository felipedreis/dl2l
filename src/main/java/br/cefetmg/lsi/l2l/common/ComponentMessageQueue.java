package br.cefetmg.lsi.l2l.common;

import akka.actor.ActorRef;
import akka.actor.PoisonPill;
import akka.dispatch.Envelope;
import akka.dispatch.MessageQueue;
import br.cefetmg.lsi.l2l.creature.bd.PersistenceState;
import br.cefetmg.lsi.l2l.stimuli.Stimulus;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.Collectors;

/**
 * Created by felipe on 03/01/17.
 */
public class ComponentMessageQueue implements MessageQueue {

    private final Queue<Envelope> queue = new ConcurrentLinkedQueue<Envelope>();

    public ComponentMessageQueue(){
    }

    public void enqueue(ActorRef actorRef, Envelope envelope) {
        queue.offer(envelope);
    }

    public Envelope dequeue() {

        List list = new ArrayList<>();
        Envelope env = queue.peek();

        if (env != null && env.message() instanceof PoisonPill) {
            System.out.println("Poison pill found");
            return queue.poll();
        }

        while (!queue.isEmpty()) {
            env = queue.peek();

            if (env.message() instanceof Stimulus || env.message() instanceof PersistenceState) {
                list.add(env.message());
                queue.poll();
            } else if (env.message() instanceof String) {
                queue.poll();
            } else if(env.message() instanceof PoisonPill) {
                System.out.println("Next message is a poison pill, handle previous messages first");
                break;
            }
        }

        return Envelope.apply(list, ActorRef.noSender());
    }

    public int numberOfMessages() {
        return queue.size();
    }

    public boolean hasMessages() {
        return !queue.isEmpty();
    }

    public void cleanUp(ActorRef owner, MessageQueue deadLetters) {
        for (Envelope handle : queue) {
            deadLetters.enqueue(owner, handle);
        }
    }
}
