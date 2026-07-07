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

# Preferred: extract directly from PostgreSQL (no fat JAR needed)
python3 scripts/pg_extract.py --out <output-dir> --container <db-container>
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

Internal creature components omit the `Actor` suffix in their class names (e.g. `HomeostaticRegulation`, `PartialAppraisal`, `MemoryConsolidator`). The `Actor` suffix is reserved for cluster-level or infrastructure actors (e.g. `CollisionDetectorActor`, `MemorySystemActor`).

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

New JEPA-integration analysis scripts (`coverage_probe.py`, `reg_granularity.py`, …) are Python 3 + pandas + sklearn + matplotlib and follow the same `wd` convention. Run them with `python3 analysis/<script>.py`.

## JEPA World Model

The `ml/` directory contains everything for the species base world model:

```
ml/
  jepa/             # PyTorch modules: model.py, train.py, export.py, dataset.py, evaluate.py
  scripts/          # CLI entry points:
    prepare_dataset.py   # assemble (s_t, a_t, emotion_target) tuples from CSV → parquet
    train_species.py     # train single|dual|internal_critic|internal_predictor variant
    check_collapse.py    # verify encoder did not collapse
    export_model.py      # trace to TorchScript + write model_contract.json
    upload_hf.py         # push models to HF model repo + dataset to HF dataset repo
```

### HuggingFace repositories

| Repository | URL | Contents |
|---|---|---|
| **dl2l-jepa** (model) | `https://huggingface.co/felipedreis/dl2l-jepa` | TorchScript `.pt` + `model_contract.json` per variant |
| **dl2l-experiments** (dataset) | `https://huggingface.co/datasets/felipedreis/dl2l-experiments` | Parquet files + `stats.json`, one prefix per experiment (e.g. `p9/`) |

To upload after training:
```bash
cd ml
python3 -m scripts.upload_hf \
    --repo felipedreis/dl2l-jepa \
    --data-repo felipedreis/dl2l-experiments \
    --ckpt checkpoints_p9 --data data_p9 --data-prefix p9
```

Model variants (all trained on p9 data, best val L_pred):
- `internal_critic` — 0.1683 (predictor: world-only; critic: world+internal)
- `single`          — 0.1732 (predictor: world-only; critic: world-only)
- `internal_predictor` — 0.1750 (predictor: world+internal; critic: world-only)
- `dual`            — 0.1884 (predictor: world+internal; critic: world+internal)

Exported artifacts live in `src/main/resources/models/{variant}/` and are bundled into the fat JAR. The Java runtime selects the variant from `model_contract.json`'s `model_variant` field.

## Akka Actor Anti-Patterns

**Never share object instances directly between actors.** Actors must only communicate with the outside world through message passing. Holding a direct reference to a shared mutable object (e.g. a static singleton, a shared service instance) bypasses Akka's supervision hierarchy, breaks thread-safety guarantees, and is an anti-pattern.

**Akka-idiomatic patterns for per-JVM singletons:**

- **Akka Extension** (`AbstractExtensionId<T>`) — the canonical way to create a resource that is initialized exactly once per `ActorSystem` (= per JVM in DL2L). The extension class holds the heavy resource (e.g. a loaded ML model) and exposes an `ActorRef` to a dedicated service actor. Other actors get the ref via `MyExtension.get(context().system())`. No string-based lookup; lifecycle is tied to the ActorSystem.
- **Well-known named actor + `actorSelection`** — simpler; start the actor once in `preStart()` via `system.actorOf(props, "serviceName")`, resolve elsewhere via `context().actorSelection("/user/serviceName")`. Already used in this codebase for `manager` and `collisionDetector`.
- **Akka Cluster Singleton** (`ClusterSingletonManager`) — one actor per *cluster* (not per JVM). Use only when you truly need exactly one instance across all nodes.

The Extension pattern is preferred for ML services because it guarantees one load per JVM node and avoids string-based actor selection.

## Development cycle

Before implementing a feature:
0. user will prompt you with the issue/epic id in gitlab to start working
1. You'll read the relevant GitHub issue(s) and the HLD (`docs/hld/`) for design rationale.
2. Write an implementation plan to `docs/plans/<descriptive-name>.md` (not in `~/.claude/plans/`)
    Note: this step is mandory, NEVER skip it and start implementing immediatelly
3. Get the plan approved, then implement.
4. Verify with `mvn package` (must compile clean) and, where applicable, a simulation run + extractor run.
5. For testing you should run mini-experiments to verify the HLD hipotesis 
    a. You should state the hipotesis and assumptions to the user
    b. You should choose a relevant sample to test the hipotesis 
    c. Whenever you can determine the size of the sample through an statistical method, do it
    d. You should create the experiment configuration and run it through docker
    e. You should collect the data and analyse it with python
    f. You should finally create a report in docs/reports folder 
    g. The report should have the following sections: Purpose, Assumptions, Hypothesis, Results and Analysis 
    h. You should include all the graphs and figures needed in your report
    i. Upload all data collected during the experiment to `felipedreis/dl2l-experiments` on HuggingFace
       under the prefix `p{issue-number}/` (e.g. `p55/` for issue #55). Use `hf upload` or
       `ml/scripts/upload_hf.py`. Never discard experiment data — it must be preserved on HF.
