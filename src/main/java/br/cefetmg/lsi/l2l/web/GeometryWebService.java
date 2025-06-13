package br.cefetmg.lsi.l2l.web;

import akka.NotUsed;
import akka.actor.ActorSystem;
import akka.http.javadsl.ConnectHttp;
import akka.http.javadsl.Http;
import akka.http.javadsl.ServerBinding;
import akka.http.javadsl.model.ws.Message;
import akka.http.javadsl.model.ws.TextMessage;
import akka.http.javadsl.server.AllDirectives;
import akka.http.javadsl.server.Route;
import akka.stream.Materializer;
import akka.stream.javadsl.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import br.cefetmg.lsi.l2l.physics.CreatureGeometry;
import br.cefetmg.lsi.l2l.physics.ObjectGeometry;

import java.util.concurrent.CompletionStage;

public class GeometryWebService extends AllDirectives {
    private final ActorSystem system;
    private final Materializer materializer;

    private final ObjectMapper objectMapper;
    private final Source<GeometryUpdate, NotUsed> geometrySource;

    public GeometryWebService(ActorSystem system,
                            Materializer materializer,
                            Source<CreatureGeometry, NotUsed> creatureSource,
                            Source<ObjectGeometry, NotUsed> objectSource) {
        this.system = system;
        this.materializer = materializer;
        this.objectMapper = new ObjectMapper();
        Source<GeometryUpdate, NotUsed> creatures = creatureSource.map(GeometryUpdate::fromCreature);
        Source<GeometryUpdate, NotUsed> objects = objectSource.map(GeometryUpdate::fromObject);

        // Merge both sources and map to GeometryUpdate
        this.geometrySource = creatures.merge(objects);
    }

    public Route createRoute() {
        return path("geometry", () ->
            handleWebSocketMessages(websocketFlow())
        );
    }

    private Flow<Message, Message, NotUsed> websocketFlow() {
        return Flow.<Message>create()
            .merge(geometrySource
                .map(update -> {
                    try {
                        return TextMessage.create(objectMapper.writeValueAsString(update));
                    } catch (Exception e) {
                        return TextMessage.create("error");
                    }
                })
            );
    }

    public CompletionStage<ServerBinding> start(String host, int port) {
        return Http.get(system)
                .bindAndHandle(
                        createRoute().flow(system, materializer),
                        ConnectHttp.toHost(host, port),
                        materializer
                )
                .thenApply(binding -> {
                    system.log().info("Server started at http://{}:{}/", host, port);
                    return binding;
                })
                .exceptionally(ex -> {
                    system.log().error("Failed to bind server: {}", ex.getMessage());
                    throw new RuntimeException("Server failed to start", ex);
                });

    }
}