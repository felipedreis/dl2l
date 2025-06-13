package br.cefetmg.lsi.l2l.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import br.cefetmg.lsi.l2l.physics.CreatureGeometry;
import br.cefetmg.lsi.l2l.physics.ObjectGeometry;

public class GeometryUpdate {
    @JsonProperty("type")
    private final String type;
    @JsonProperty("id")
    private final String id;
    @JsonProperty("x")
    private final double x;
    @JsonProperty("y")
    private final double y;
    @JsonProperty("objectType")
    private final String objectType;

    public static GeometryUpdate fromCreature(CreatureGeometry geometry) {
        return new GeometryUpdate(
            "creature",
            geometry.id.toString(),
            geometry.getX(),
            geometry.getY(),
            null
        );
    }

    public static GeometryUpdate fromObject(ObjectGeometry geometry) {
        return new GeometryUpdate(
            "object",
            geometry.id.toString(),
            geometry.getX(),
            geometry.getY(),
            geometry.type.toString()
        );
    }

    private GeometryUpdate(String type, String id, double x, double y, String objectType) {
        this.type = type;
        this.id = id;
        this.x = x;
        this.y = y;
        this.objectType = objectType;
    }
}