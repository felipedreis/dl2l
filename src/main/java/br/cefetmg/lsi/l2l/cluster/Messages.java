package br.cefetmg.lsi.l2l.cluster;

import br.cefetmg.lsi.l2l.common.SequentialId;
import br.cefetmg.lsi.l2l.world.WorldObjectType;

import java.io.Serializable;

final class CreateWorldObject implements Serializable {

    public final WorldObjectType type;
    public final SequentialId id;


    CreateWorldObject(WorldObjectType type, SequentialId id) {
        this.type = type;
        this.id = id;
    }
}

final class CreateCreature implements Serializable {
    public final SequentialId id;

    CreateCreature(SequentialId id) {
        this.id = id;
    }
}

final class HolderLookup implements Serializable {
    public final long id;

    HolderLookup(long id) {
        this.id = id;
    }
}

final class Register implements Serializable {
    public final String role;

    Register(String role) {
        this.role = role;
    }
}

final class AskForId implements Serializable {}

final class AskForIds implements Serializable {
    public final int quantity;

    AskForIds(int quantity) {
        this.quantity = quantity;
    }
}

final class AckReady implements Serializable {}

final class Ready implements  Serializable {
    public final boolean ready;

    Ready(boolean ready) {
        this.ready = ready;
    }
}

final class AllCreaturesDead implements Serializable {
    public final long id;

    AllCreaturesDead(long id) {
        this.id = id;
    }
}

final class Finish implements Serializable{}

final class Repose implements Serializable {
    public final WorldObjectType objectType;

    public Repose(WorldObjectType objectType) {
        this.objectType = objectType;
    }
}