package br.cefetmg.lsi.l2l.cluster;

import br.cefetmg.lsi.l2l.common.SequentialId;
import br.cefetmg.lsi.l2l.world.WorldObjectType;

import java.io.Serializable;
import java.util.List;

record CreateWorldObjects(WorldObjectType type, List<SequentialId> id) implements Serializable {
}

record CreateWorldObject(WorldObjectType type, SequentialId id) implements Serializable {
}

record CreateCreature(SequentialId id) implements Serializable {
}

record HolderLookup(long id) implements Serializable {
}

record Register(String role) implements Serializable {
}

final class AskForId implements Serializable {}

record AskForIds(int quantity) implements Serializable {
}

final class AckReady implements Serializable {}

record Ready(boolean ready) implements Serializable {
}

record AllCreaturesDead(long id) implements Serializable {
}

final class Finish implements Serializable{}

record Repose(WorldObjectType objectType) implements Serializable {
}