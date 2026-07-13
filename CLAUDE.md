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

# Run via Docker Compose (ad-hoc single-stack dev convenience — no experiment tracking)
cd docker && docker-compose up

# Build Docker image
docker build -f docker/Dockerfile -t dl2l .

# Run an experiment (any env — see "Running Experiments" below)
cd ansible && ansible-playbook -i inventories/local run-experiment.yml -e experiment=<name>

# Extract data directly from a running PostgreSQL container (used internally by the
# ansible trial runner; call it manually to re-extract from a live container)
PYTHONPATH=scripts python3 -m dl2l_data.extract \
    --experiment <name> --condition <key> --trial <n> --out <data-dir> --container <db-container>
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
- URL: `jdbc:postgresql://dl2l-db:5432/l2l` (hardcoded default — Docker's per-container DNS
  resolves `dl2l-db` in every environment that uses docker-compose, i.e. local and Pi)
- User/password: `postgres`/`postgres`
- DDL generation: `drop-and-create-tables` on startup

`DL2L_DB_URL` (read in `JpaPersister`'s constructor) overrides the URL above when set — needed
on CCAD, where Singularity instances share the node's network namespace instead of getting
per-container hostnames like Docker, so postgres is reached at `jdbc:postgresql://localhost:5432/l2l`
instead. Unset (the default everywhere else), behavior is unchanged.

All JPA entities are in `creature/bd/` and `common/SequentialId`. EclipseLink is the JPA provider.

## Running Experiments

Experiments are declared once as a YAML spec and run identically on any of three
environments — this local macbook, the Raspberry Pi cluster (SLURM + Docker), or CCAD
(CEFET-MG's HPC cluster, SLURM + Singularity/Apptainer — the only CEFET cluster;
`cluster.decom.cefetmg.br` no longer exists) — via a single Ansible playbook. This
replaced a prior generation of ~46 near-duplicate `docker-compose-*.yml` files and ~10
`run_exp_*.sh` scripts (still visible in git history if you need to reference an old
one-off run).

```bash
cd ansible
ansible-playbook -i inventories/local run-experiment.yml -e experiment=<name>   # this Mac
ansible-playbook -i inventories/pi    run-experiment.yml -e experiment=<name>   # Pi cluster
ansible-playbook -i inventories/ccad  run-experiment.yml -e experiment=<name>   # CCAD (see below — submit/rescue)
```

An experiment is one file, `experiments/<name>.yml` — conditions (each mapping to a
`simulations/*.conf`), trial count, image source, extraction/upload settings, and
(optionally) which `analysis/experiments/<module>.py` to run. Schema and a worked
example are in `experiments/README.md`. Validate a spec standalone with
`python3 scripts/validate_experiment.py experiments/<name>.yml`.

The playbook always: loads and validates the spec → builds/pulls the image → runs
every (condition, trial) cell (compose up → wait for the holder to finish → extract →
teardown) → uploads the collected data to `felipedreis/dl2l-experiments` on
HuggingFace (never skip this — see Development cycle step 5i) → optionally runs the
analysis module (`-e analyze=true`).

Local runs always publish the UI on `:8080` (the local inventory sets
`publish_ui: true`); the Pi and CCAD inventories don't.

**CCAD requires the CEFET VPN, which drops the connection when idle** — same
constraint as JEPA training there (see below). Running experiments on CCAD is
submit-and-collect, not submit-and-wait: a plain run submits one SLURM array job per
condition (Singularity instances standing in for docker-compose — no compose
equivalent exists under Singularity, so each of the 4 roles runs as its own
`singularity instance` sharing the node's network namespace, `HOST=localhost` with a
distinct port per role) and returns immediately; a later `-e rescue=true` run (safe to
repeat) checks whether every condition's every trial has finished (a `DONE` sentinel
each array task writes on exit) and, once all are done, syncs the data back and
proceeds to upload/analyze/train. CCAD images come from GHCR only (no local docker
daemon there — `image: {source: registry}` is required for `experiments/*.yml` specs
targeting CCAD). See `training/README.md` for the shared submit/rescue pattern and
`docs/plans/ccad-singularity-experiments.md` for the full design, including two
untested-against-the-live-cluster risks flagged there: running the official postgres
image under unprivileged Singularity, and detecting simulation completion without a
`docker wait` equivalent.

### Extraction and upload internals

`scripts/dl2l_data/` is the extraction/upload package the ansible roles call:
- `dl2l_data.extract` — `docker exec <container> psql ... COPY` per table (or, with
  `--runtime singularity` on CCAD, `singularity exec instance://<name> psql ...` —
  reusing the psql client bundled in the postgres image itself, since there's no
  reason to assume a bare psql client exists on the cluster host) → one Parquet file
  per table under `<data-dir>/<condition>/trial_N/`, plus a gzipped `pg_dump` backup
  and a root `manifest.json`.
- `dl2l_data.upload` — pushes a data-dir tree to the HF dataset repo.
- `scripts/pg_extract.py` is a separate, older CSV-oriented extractor (mirrors the
  Java `--extractor` output format exactly) kept for parity with historical data; it
  shares its low-level `psql`/`pg_dump` helpers with `dl2l_data.db`.

## WebSocket API (feature/api branch)

`CollisionDetectorActor` feeds position updates into a `GeometrySourceProvider` (Akka Streams queue). `GeometryWebService` merges creature and object streams and broadcasts `GeometryUpdate` JSON over WebSocket at `ws://localhost:8080/geometry`.

## Data Analysis

`analysis/dl2l_analysis/` is the shared library for analyzing an experiment's Parquet
output: `config.py` (`ExperimentAnalysis.from_spec("<name>")` reads
`experiments/<name>.yml` — conditions, labels, colors, trials, data/fig/report dirs),
`loading.py` (`load_all`, the born_time/elapsed_s/tick-rank enrichment scaffold),
`stats.py` (`cond_stats`, `kruskal_test` — Kruskal-Wallis + Bonferroni-corrected
Mann-Whitney), `figures.py` (Agg backend setup, palette-aware save-to-`FIG_DIR`), and
`report.py` (the mandated Purpose/Assumptions/Hypothesis/Results/Analysis skeleton —
see Development cycle step 5g).

A new experiment's analysis script goes in `analysis/experiments/<name>.py` — a `run(cfg)`
function that pulls in only the experiment-specific figures/interpretation; everything
generic comes from `dl2l_analysis`. See `analysis/experiments/rotten_fruit_v1.py` for
the pattern. Run it via:

```bash
PYTHONPATH=analysis python3 -m dl2l_analysis --experiment <name>
```

(or pass `-e analyze=true` to the ansible experiment playbook to run it automatically
after data collection).

`analysis/legacy/` holds the pre-refactor CSV-era scripts (`exp1.py`, `util.py`,
`graphics.py`, …) for old experiments whose raw CSV data still lives in their original
layout — kept for provenance, not meant to be extended. `analysis/exp_20260709_memory_vs_wm_v1.py`
is a not-yet-ported Parquet-era script (the `dl2l_analysis` port for it doesn't exist
yet); run it directly with `python3 analysis/exp_20260709_memory_vs_wm_v1.py` until
someone ports it.

## JEPA World Model

The `ml/` directory contains everything for the species base world model:

```
ml/
  jepa/             # PyTorch modules: model.py, train.py, export.py, dataset.py, evaluate.py
  scripts/          # CLI entry points (orchestrated by ansible/train-model.yml — see below):
    prepare_dataset.py   # assemble (s_t, h_t, a_t, final_*) tuples from Parquet; writes both
                         # plain (train.parquet/val.parquet, for --variant single) and dual
                         # (train_dual.parquet/val_dual.parquet, for the other 4 variants)
    train_species.py     # train single|dual|internal_critic|internal_predictor|unified_critic variant
    check_collapse.py    # verify encoder did not collapse (hard gate — exit 1 = fail)
    export_model.py      # trace to TorchScript + write model_contract.json
    upload_hf.py         # push models to HF model repo + dataset to HF dataset repo
```

### HuggingFace repositories

| Repository | URL | Contents |
|---|---|---|
| **dl2l-jepa** (model) | `https://huggingface.co/felipedreis/dl2l-jepa` | TorchScript `.pt` + `model_contract.json` per variant |
| **dl2l-experiments** (dataset) | `https://huggingface.co/datasets/felipedreis/dl2l-experiments` | Parquet files + `stats.json`, one prefix per experiment (e.g. `p9/`) |

### Training via Ansible

Training is declared as a `training/<name>.yml` spec (which experiment's already-collected
data to train from, which variants, hyperparameters) and run via the same Ansible pattern
as experiments — standalone, or chained right after data collection:

```bash
cd ansible
ansible-playbook -i inventories/local train-model.yml -e training=<name>   # this Mac

# CCAD (GPU cluster, https://www.ccad.cefetmg.br/guia/ — NOT cluster.decom.cefetmg.br,
# which is CPU-only and used only for simulations): requires the CEFET VPN, which drops
# on idle, so this is submit-then-collect, not submit-and-wait. Username comes from a
# gitignored .env.local (CCAD_USERNAME=<cpf>, see training/README.md), not a flag:
ansible-playbook -i inventories/ccad train-model.yml -e training=<name>              # submit, returns immediately
ansible-playbook -i inventories/ccad train-model.yml -e training=<name> -e rescue=true  # later: collect + upload

# or chain it onto a data-collection run (experiment spec needs a `training: <name>` field):
ansible-playbook -i inventories/local run-experiment.yml -e experiment=<name> -e train=true
```

Schema, worked example, and pipeline details are in `training/README.md`. Validate a spec
standalone with `python3 scripts/validate_training.py training/<name>.yml`. `prepare_dataset.py`
always runs locally (pandas-only, no GPU needed); only `train_species.py` → `check_collapse.py`
(hard gate — non-zero exit aborts the run) → `export_model.py` moves to CCAD when training
there. `export_model.py` writes straight into `src/main/resources/models/<variant>/` — that IS
the "save the latest version in the repo" step, no separate copy needed — and `upload_hf.py`
(pointed at that same directory, which already has the `<variant>/*.pt + *.json` shape it
expects) pushes to HF afterward. Training does not run on the Pi cluster or on
`cluster.decom.cefetmg.br` (no realistic compute there for this workload).

Model variants (all trained on p9 data, best val L_pred):
- `internal_critic` — 0.1683 (predictor: world-only; critic: world+internal)
- `single`          — 0.1732 (predictor: world-only; critic: world-only)
- `internal_predictor` — 0.1750 (predictor: world+internal; critic: world-only)
- `dual`            — 0.1884 (predictor: world+internal; critic: world+internal)
- `unified_critic`  — internal_critic with predictor+critic merged into one exported module (in active use)

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
    d. Write an experiment spec at `experiments/p{issue-number}_<name>.yml` (see
       `experiments/README.md` for the schema) and run it via
       `cd ansible && ansible-playbook -i inventories/local run-experiment.yml -e experiment=p{issue-number}_<name>`
       (see "Running Experiments" above).
    e. The playbook collects the data automatically; write the analysis as
       `analysis/experiments/p{issue-number}_<name>.py` using `analysis/dl2l_analysis/`
       (see "Data Analysis" above), and run it standalone or via `-e analyze=true`.
    f. You should finally create a report in docs/reports folder 
    g. The report should have the following sections: Purpose, Assumptions, Hypothesis, Results and Analysis 
    h. You should include all the graphs and figures needed in your report
    i. Experiment data is uploaded to `felipedreis/dl2l-experiments` on HuggingFace
       automatically by the playbook's collect-and-upload play (`upload.prefix` in the
       experiment spec, default `p{issue-number}/` — e.g. `p55/` for issue #55). Never
       set `upload.enabled: false` for a real experiment — data must be preserved on HF.
