package br.cefetmg.lsi.l2l.gui;

import akka.NotUsed;
import akka.actor.ActorSystem;
import akka.http.javadsl.Http;
import akka.http.javadsl.model.ws.Message;
import akka.http.javadsl.model.ws.TextMessage;
import akka.http.javadsl.server.AllDirectives;
import akka.http.javadsl.server.Route;
import akka.stream.ActorMaterializer;
import akka.stream.javadsl.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import br.cefetmg.lsi.l2l.physics.CreatureGeometry;
import br.cefetmg.lsi.l2l.physics.ObjectGeometry;

public class GeometryWebService extends AllDirectives {
    private final ActorSystem system;
    private final ObjectMapper objectMapper;
    private final Source<GeometryUpdate, NotUsed> geometrySource;

    public GeometryWebService(ActorSystem system, 
                            Source<CreatureGeometry, NotUsed> creatureSource,
                            Source<ObjectGeometry, NotUsed> objectSource) {
        this.system = system;
        this.objectMapper = new ObjectMapper();
        
        // Merge both sources and map to GeometryUpdate
        this.geometrySource = Source.merge(
            creatureSource.map(GeometryUpdate::fromCreature),
            objectSource.map(GeometryUpdate::fromObject)
        );
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

    public void start(String host, int port) {
        Http.get(system).newServerAt(host, port).bind(createRoute());
    }
}