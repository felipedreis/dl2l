package br.cefetmg.lsi.l2l.cluster;

import akka.actor.AbstractActor;
import akka.actor.ActorRef;
import akka.actor.TypedActor;
import akka.actor.UntypedActor;
import akka.cluster.Cluster;
import akka.cluster.ClusterEvent.*;
import akka.cluster.Member;
import akka.japi.pf.ReceiveBuilder;
import br.cefetmg.lsi.l2l.common.SequentialId;
import scala.concurrent.Await;
import scala.concurrent.Future;
import scala.concurrent.duration.FiniteDuration;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * Created by felipe on 27/03/17.
 */
public class SequentialIdProvider extends AbstractActor implements Registrable {

    private final Cluster cluster = Cluster.get(context().system());
    private final Logger logger = Logger.getLogger(SequentialIdProvider.class.getName());
    private long lastKey = 0;

    public SequentialIdProvider(){}

    @Override
    public void preStart() throws Exception {
        super.preStart();
        cluster.subscribe(self(), MemberUp.class);
    }

    @Override
    public void postStop() throws Exception {
        super.postStop();
        cluster.unsubscribe(self());
        logger.info("Id provider stopped");
    }

    @Override
    public Receive createReceive() {
        return ReceiveBuilder.create()
                .match(AskForId.class, (request) -> {
                    SequentialId id = provide();
                    sender().tell(id ,self());
                })
                .match(AskForIds.class, (request) -> {
                    List<SequentialId> ids = provideMany(request.quantity);
                    sender().tell(ids, self());
                })
                .match(Finish.class, finish -> {
                    logger.info("Got stop order from master");
                    context().stop(self());
                    context().system().terminate();
                })
                .match(MemberUp.class, memberUp -> {
                    handleNewMember(memberUp.member());
                })
                .build();
    }

    private List<SequentialId> provideMany(int quantity) {
        List<SequentialId> ids = new ArrayList<>();
        for (int i = 0; i < quantity; ++i) {
            ids.add(provide());
        }
        return ids;
    }

    private SequentialId provide() {
        if(lastKey < 0) {
            throw new IllegalStateException("Maximum of id\'s provided");
        }

        SequentialId id = new SequentialId(lastKey);
        lastKey++;
        return id;
    }

    @Override
    public void handleNewMember(Member member) {
        if (member.hasRole("manager")) {
            FiniteDuration duration  = FiniteDuration.apply(5, "seconds");
            Future managerFuture = context().actorSelection(member.address().toString() + "/user/manager")
                    .resolveOne(duration);
            try {
                ActorRef manager =  (ActorRef) Await.result(managerFuture, duration);
                manager.tell(new Register("idProvider"), self());
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }
}
