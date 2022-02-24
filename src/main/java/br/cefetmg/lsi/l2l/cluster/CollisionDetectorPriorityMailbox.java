package br.cefetmg.lsi.l2l.cluster;

import akka.actor.ActorSystem;
import akka.dispatch.PriorityGenerator;
import akka.dispatch.UnboundedStablePriorityMailbox;

import com.typesafe.config.Config;

/**
 * Created by felipe on 28/08/17.
 */
public class CollisionDetectorPriorityMailbox extends UnboundedStablePriorityMailbox{
    public CollisionDetectorPriorityMailbox(ActorSystem.Settings settings, Config config) {
        super(new PriorityGenerator() {
            @Override
            public int gen(Object message) {
                return message instanceof Finish ? 0 : 1;
            }
        });
    }
}
