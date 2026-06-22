# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What This Project Is

DL2L (Distributed - Live to Learn, Learn to Live) is a distributed artificial life simulator. Creatures with cognitive subsystems (eyes, nose, mouth, emotional system, memory, operant conditioning) inhabit a shared world and interact with food objects. The system runs as an Akka cluster with four distinct node roles.

## Build & Run Commands

```bash
# Build (produces target/l2l-2.0.0-SNAPSHOT-wd.jar — fat jar with all deps)
mvn package

# Run tests
mvn test

# Run a single test class
mvn test -Dtest=QuadTreeTest

# Run locally (four separate terminals, in this order):
./scripts/manager.sh       # port 2551, roles: manager
./scripts/provider.sh      # roles: idProvider
./scripts/detector.sh      # roles: collisionDetector
./scripts/holder.sh        # roles: holder

# Run via Docker Compose (preferred for local dev)
cd docker && docker-compose up

# Build Docker image
docker build -f docker/Dockerfile -t dl2l .

# Run data extractor (exports simulation data to CSV)
java -jar target/l2l-2.0.0-SNAPSHOT-wd.jar --host localhost --port 2551 --roles holder --extractor --save <output-dir>
```

The generic launch script signature:
```bash
java -Dconfig.file=<akka-config> -jar dl2l.jar \
  --host <ip> --port <port> --roles <role1> [role2...] \
  --simulation <simulations/basic.conf> --save <data-dir>
```

## Cluster Architecture

The simulation runs as four cooperating Akka actor roles — each can run on a separate JVM/machine:

| Role | Actor | Responsibility |
|------|-------|----------------|
| `manager` | `SimulationManager` | Orchestrates startup; distributes creatures/world-objects to holders; detects simulation end |
| `idProvider` | `SequentialIdProvider` | Issues globally unique `SequentialId` values across the cluster |
| `collisionDetector` | `CollisionDetectorActor` | Maintains a `QuadTree` of all object positions; answers proximity queries from creatures; streams geometry updates via Akka Streams |
| `holder` | `Holder` | Owns a shard of creatures and world-objects; runs creature lifecycle; persists state to PostgreSQL via EclipseLink JPA |

Startup sequence enforced by `SimulationManager`: wait for all expected holders → `AckReady` handshake → distribute world-objects → spawn creatures → simulation runs until all creatures die → `Finish` message terminates the cluster.

## Creature Architecture

Each creature is an Akka Typed actor (`CreatureActor`) that owns a set of component actors:

- **Sensors**: `Eye`, `Nose`, `Mouth` — perceive the world, interact with objects
- **Cortex**: `SensoryCortex`, `EffectorCortex` — route stimuli between sensors and cognitive subsystems
- **Cognitive**: `PartialAppraisal`, `FullAppraisal`, `HomeostaticRegulation`, `Valuation`, `EmotionalSystemActor` — appraise perceptions and generate emotional responses
- **Learning**: `OperantConditioningActor` — adjusts action probabilities based on outcomes
- **Memory**: `MemorySystemActor`, `ShortTermMemory` — stores and evokes past perceptions
- **Persistence**: `BDActor` — writes creature state snapshots to the database (uses `bd-dispatcher` with a `PinnedDispatcher` and `ComponentMailbox`)

Stimuli (all in `stimuli/`) are the messages between components. Actor messages between cluster nodes are in `cluster/Messages.java`. Messages must be immutable — actor model constraint.

## Configuration

Two layers of config:

1. **Akka config** (`src/main/resources/application.conf`, or `config/docker-config.conf` for Docker): cluster seed nodes, dispatchers, remote settings. For local runs, seed node must be `akka.tcp://l2l@localhost:2551`. For Docker, it is `akka.tcp://l2l@dl2l-manager:2551`.

2. **Simulation config** (`simulations/*.conf`): number of holders, world dimensions, creature count, world-object types/quantities. Passed via `--simulation` flag at runtime.

Custom dispatchers defined in application.conf:
- `object-dispatcher` — fork-join, for world-object actors
- `bd-dispatcher` — pinned thread-pool, for database actor (`BDActor`)
- `collision-dispatcher` — uses `CollisionDetectorPriorityMailbox`
- `component-dispatcher` — uses `ComponentMailbox`

## Database

PostgreSQL. Schema name must be `data`. Connection configured in `src/main/resources/META-INF/persistence.xml`:
- URL: `jdbc:postgresql://l2l-db:5432/l2l` (Docker) or `localhost` (local)
- User/password: `postgres`/`postgres`
- DDL generation: `drop-and-create-tables` on startup

All JPA entities are in `creature/bd/` and `common/SequentialId`. EclipseLink is the JPA provider.

## WebSocket API (feature/api branch)

`CollisionDetectorActor` feeds position updates into a `GeometrySourceProvider` (Akka Streams queue). `GeometryWebService` merges creature and object streams and broadcasts `GeometryUpdate` JSON over WebSocket at `ws://localhost:8080/geometry`.

## Data Analysis

Python 2.7 scripts in `analysis/`. After simulation, copy the SLURM output directory back locally, set the `wd` variable in `exp1.py` / `exp2.py` / `exp3.py` / `tracing.py`, and run. Requires `numpy` and `scipy`.
