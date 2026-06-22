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

import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.stream.Collectors;

public class GeometryWebService extends AllDirectives {
    private final ActorSystem system;
    private final Materializer materializer;

    private final ObjectMapper objectMapper;
    private final GeometrySourceProvider provider;
    private final Source<GeometryUpdate, NotUsed> geometrySource;

    public GeometryWebService(ActorSystem system,
                              Materializer materializer,
                              GeometrySourceProvider provider) {
        this.system = system;
        this.materializer = materializer;
        this.objectMapper = new ObjectMapper();
        this.provider = provider;

        Source<GeometryUpdate, NotUsed> creatures = provider.getCreatureSource().map(GeometryUpdate::fromCreature);
        Source<GeometryUpdate, NotUsed> objects   = provider.getObjectSource().map(GeometryUpdate::fromObject);
        Source<GeometryUpdate, NotUsed> removals  = provider.getRemovalSource().map(id -> GeometryUpdate.remove(id.toString()));

        this.geometrySource = creatures.merge(objects).merge(removals);
    }

    public Route createRoute() {
        return concat(
            path("geometry", () -> handleWebSocketMessages(websocketFlow())),
            pathSingleSlash(() -> getFromResource("static/SimulationViewer.html")),
            path("SimulationRenderer.js", () -> getFromResource("static/SimulationRenderer.js"))
        );
    }

    private Flow<Message, Message, NotUsed> websocketFlow() {
        // Replay current objects immediately for this connection, then continue with live stream.
        List<Message> snapshot = provider.getObjectSnapshot().stream()
            .map(obj -> (Message) toMessage(GeometryUpdate.fromObject(obj)))
            .collect(Collectors.toList());

        Source<Message, NotUsed> snapshotSource = Source.from(snapshot);
        Source<Message, NotUsed> liveSource = geometrySource.map(this::toMessage);

        return Flow.<Message>create()
            .merge(snapshotSource.concat(liveSource));
    }

    private Message toMessage(GeometryUpdate update) {
        try {
            return TextMessage.create(objectMapper.writeValueAsString(update));
        } catch (Exception e) {
            return TextMessage.create("error");
        }
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