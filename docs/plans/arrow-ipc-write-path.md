# Arrow IPC write path — decouple persistence from simulation, definitively

## Context

MR #81 removed JPA/Postgres, pivoted to direct Parquet writing (`ParquetBackend` + `TunedParquetWriter`), and capped the cognitive cycle at `TARGET_CYCLE_HZ=30` with dt-weighted metabolism. That fixed the local OOM, but CCAD still OOMs at 10-creature scale: 6 CPUs/trial vs ~10 locally, hardcoded `-Xmx2g` in `scripts/run-dl2l.sh`, no `#SBATCH --mem`, up to 9 co-located trials/node. The remaining unbounded memory sink is `BDActor`'s mailbox (3.3M envelopes measured before crashes — the envelopes themselves are a GC bomb), and Parquet encoding (dictionary + Snappy, on one pinned thread, on-heap row-group buffers) is CPU-expensive exactly where CPU is scarce.

Fix, per user decisions:
- **Arrow IPC** raw dump (near-zero encode cost, **off-heap** buffers — outside `-Xmx`, invisible to G1); **Parquet stays** as the extraction output (HF-compatible, analysis untouched).
- **No feedback loop into the simulation** (no adaptive tick degradation, no blocking queue): rely on cheap writes + env-sized heap + producer-side buffering, and if backlog still explodes, **abort loudly in seconds** instead of 13 minutes of GC livelock.
- **Producer-side buffering** in `CreatureComponent.persist()` to slash mailbox pressure (user's addition).
- **`ParquetBackend` removed** once Arrow validates; `PersistenceBackend` strategy interface stays for future extraction paths.

Split into 2 PRs. PR 1 = write path + resilience + env plumbing + extractor input switch (writer and extractor must switch together or trials become unextractable). PR 2 = read-path cleanup.

---

## PR 1 — write path, resilience, env plumbing

### W1. `TableSchemas` — single declarative schema (extensibility pillar)

New `src/main/java/br/cefetmg/lsi/l2l/creature/bd/TableSchemas.java` replacing four hand-synced copies (ParquetBackend's `TABLE_COLUMNS` + 460-line `DEHYDRATOR` switch, `BDActor.tableFor()` 22-way switch, `PersistenceExtension.Impl.TABLES`, Python `RAW_TABLES`):

```java
enum ColType { STRING, INT32, INT64, DOUBLE, BOOLEAN }
record Column<T extends PersistenceState>(String name, ColType type, Function<T,Object> getter) {}
record TableDef<T extends PersistenceState>(String table, Class<T> stateClass, List<Column<T>> columns) {}
static final List<TableDef<?>> ALL;               // 22 defs, "id" first
static TableDef<?> forState(PersistenceState ps);  // class-keyed map
```

Port the 22 column lists/getters verbatim from `ParquetBackend.java` (incl. two-column `SequentialId` expansion, null-safe UUID/enum getters). `BDActor.tableFor()` delegates; a future backend consumes `ALL` and never touches per-state code. Python needs no schema copy — Arrow IPC files are self-describing.

### W2. `ArrowIpcBackend` (new impl of `PersistenceBackend`); delete `ParquetBackend`/`TunedParquetWriter`

- **Deps** (pom.xml): `org.apache.arrow:arrow-vector`, `arrow-memory-core`, `arrow-memory-unsafe` (18.x; verify latest on Central); `arrow-compression` only if compression enabled. Remove `blue.strategic.parquet:parquet-floor`.
- **Allocator**: `arrow-memory-unsafe` (no netty transitive jars in the fat jar). Single `RootAllocator` hard-limited by `DL2L_ARROW_ALLOCATOR_LIMIT_MB` (default 256); gauge `dl2l_arrow_allocated_bytes`.
- **JVM flag** (mandatory JDK 16+): `--add-opens=java.base/java.nio=org.apache.arrow.memory.core,ALL-UNNAMED` in `scripts/run-dl2l.sh` (all roles).
- **Structure**: per table, a `VectorSchemaRoot` (from `TableSchemas`) + `ArrowStreamWriter` → `<saveDir>/raw/<table>.arrow`; record batch flushed every `DL2L_ARROW_BATCH_ROWS` (default 4096, ≈1MB/table worst case, off-heap).
- **Stream format, not File format** (deliberate): a killed JVM leaves data readable up to the last complete batch (`pyarrow.ipc.open_stream`) — the raw dump keeps its "backup" role.
- **Compression off by default** (`DL2L_ARROW_COMPRESSION=none|lz4|zstd`): raw dir is node-local scratch and deleted post-extraction; write CPU is the scarce resource. ~20M-row stimulus_state ≈ 4–5GB uncompressed (vs 1.5GB Snappy parquet) — acceptable; LZ4 is the Pi/SD-card escape hatch.
- **Null handling**: unset vector slot = null after `allocateNew()` — structurally kills the `writeIfPresent` NPE bug class.
- `flush()` stays a **no-op** (documented mid-run `Flush` contract from `Holder.handleRemoveObject`); `dumpToParquet()` (name kept — renaming touches Holder/messages for zero behavior; fix javadoc) finalizes idempotently.
- **Post-finalize writes**: counted + logged no-op (`DL2L-PERSISTENCE-LATE-WRITE` marker + gauge), never a crash.
- **Constructor self-check**: 1-row in-memory write+read round trip; failure throws naming the exact `--add-opens` flag → holder fails loudly at preStart.
- Fat jar: existing `ServicesResourceTransformer` should suffice; add a post-`mvn package` startup smoke check (shade 2.4.1 is old; fallback = shade bump / `module-info.class` exclusion).

### W3. Fast-fail watchdog + O(1) queue depth + loud supervision (resilience pillar)

- `ComponentMessageQueue` (`common/ComponentMessageQueue.java`): add `AtomicLong` envelope counter with public accessor (replaces O(n)-adjacent bookkeeping for the gauge; feeds the watchdog). Update its tests.
- **Watchdog**: daemon `java.lang.Thread` started in `PersistenceExtension.Impl.configure()` (immune to dispatcher starvation and still runs under GC pressure, unlike Akka-scheduled tasks). Sample every 5s; threshold `DL2L_BDACTOR_QUEUE_LIMIT` default 500,000 envelopes (healthy blips peak ~8k; every crash ≥1.2M); 2 consecutive over-threshold samples (hysteresis). On trip: SEVERE log marker `DL2L-FATAL-PERSISTENCE-OVERLOAD` (depth, arrow bytes, heap stats), gauge `dl2l_persistence_fatal=1`, `PERSISTENCE_OVERLOAD` sentinel file in the host-bound raw dir, ~2s grace, `Runtime.halt(3)`. Local: nonzero container exit → ansible fails loudly. CCAD: sentinel + `.err` marker explain the death in seconds.
- **No silent-dead-writer** (the "22 empty files, zero error" mode): watcher actor with `context().watch(bdActor)`; `Terminated` without orderly-shutdown flag → `DL2L-FATAL-PERSISTENCE-DEAD`, sentinel, `Runtime.halt(4)`. Plus try/catch around backend calls in `BDActor.onReceive` logging the marker before rethrow so the stack trace sits next to it.

### W4. Producer-side buffering in `CreatureComponent.persist()` (user's addition)

- `CreatureComponent.java:106`: plain `ArrayList<PersistenceState>` per component (components are single-threaded actors — no locking). Accumulate; at N=256 states (`DL2L_PERSIST_BUFFER_STATES`, 0 = passthrough) send one consolidated `PersistenceState[]` and clear. A cycle emits ~20–40 states across ~14 `persist()` calls at up to ~300Hz — N=256 cuts envelope rate by ~2 orders of magnitude.
- **Flush-on-death**: `ComponentActor.postStop()` (already delegates to `component.postStop()`) calls new `flushPersistBuffer()` before the subclass hook. `CreatureActor.kill()`'s final `CreatureState` write is not a component — unchanged.
- **Consolidators** (`MemoryConsolidator.java:243`, `MemoryTraceConsolidator.java:104`): already per-sleep-episode, low-rate — leave unbuffered, document at call sites.
- `expand()`/dedup unaffected (same-JVM references; coalescing strengthens the existing "one persist() = one atomic batch" invariant). Duplicate-row semantics unchanged (no upsert anywhere).
- Retune `max-states-per-batch` 2000 → 8192 in `config/docker-config.conf`, `config/ccad-config.conf`, and `src/main/resources/application.conf` bd-dispatcher blocks (with 256-state arrays, 2000 = only ~8 envelopes/dequeue; Arrow appends are cheap). Keep `max-batch-size=500`.

### W5. Env-configurable JVM + env bug fixes

- `scripts/run-dl2l.sh:17`: `-Xmx${JAVA_XMX:-2g}`, add the `--add-opens` flag, add `${JAVA_OPTS:-}` passthrough. (No `-XX:MaxDirectMemorySize` — the unsafe allocator isn't governed by it; the RootAllocator limit is the cap.)
- `Constants.TARGET_CYCLE_HZ` → env-overridable (`TARGET_CYCLE_HZ`, default 30); consumers unchanged.
- Compose templates (`docker/docker-compose.yml`, `ansible/roles/common/templates/docker-compose.yml.j2`): `JAVA_XMX: ${DL2L_JAVA_XMX:-2g}` on holder + optional `TARGET_CYCLE_HZ`.
- CCAD (`ansible/roles/trial_runner_ccad/templates/run_trial.sh.j2` + `inventories/ccad/group_vars/all.yml`): `--env JAVA_XMX={{ ccad_holder_xmx }}` (start 6g) on the holder instance, `#SBATCH --mem={{ ccad_sim_mem }}` (start 24G) — makes SLURM finally account for memory instead of letting 9 co-located trials oversubscribe a node.
- Bug fixes found during exploration:
  - Local `ansible/roles/trial_runner_local/tasks/one_trial.yml:13`: binds `.../trial_N/raw` as `/dl2l/data` while the JVM appends its own `raw` → files land in `raw/raw/` and extraction finds nothing. Bind the trial dir instead. Same fix in `docker/docker-compose.yml` (`../data`).
  - Pi `ansible/roles/trial_runner_pi/templates/dl2l_trial.sh.j2`: still passes removed `--container/--docker-cmd` flags and references the removed `dl2l-db` service — rewrite to current CLI (`--raw-dir`).
  - `CreatureActor.java:236`: stale "BDActor upserts (ON CONFLICT DO UPDATE)" comment — false since #81.

### W5b. CPU accounting and dispatcher rescale (CCAD)

Memory gets sized per environment in W5; CPU must be too — the CCAD OOM was CPU-headroom-dependent (G1 "Using 6 workers of 6", writer + GC starved). Decision: **trade trial parallelism for simulation performance/stability.**

- **Raise the CCAD CPU slice**: `ccad_sim_cpus: 6 → 14` (`inventories/ccad/group_vars/all.yml`, feeds `#SBATCH --cpus-per-task` in `run_trial.sh.j2`). 14 packs exactly 4 trials on a 56-CPU node and gives the holder a ~10-core budget — the same headroom the local validation passed with. Stays a var (10–15 range acceptable); combined with W5's `--mem`, SLURM now accounts for both resources, so co-location density drops from up-to-9 to ≤4 trials/node — the accepted trade.
- **Per-role CPU budgets via `-XX:ActiveProcessorCount`**: all four role JVMs share one cpuset today, and each sizes its G1 workers, fork-join pools (`parallelism-factor × availableProcessors`), JIT, netty, and libtorch intra-op threads as if it owned the whole slice — structural oversubscription that `parallelism-factor=1.0` only mitigated. Add `JAVA_CPUS` to `scripts/run-dl2l.sh` (`-XX:ActiveProcessorCount=$JAVA_CPUS` when set — one flag rescales every `availableProcessors`-derived pool at once). CCAD budgets on a 14-CPU slice: holder 10, collisionDetector 2, manager 1, idProvider 1, plumbed per-instance in `run_trial.sh.j2`. Unset elsewhere (local/pi behavior unchanged).
- **Dedicated fixed pool for cluster/simulation actors**: fork-join's work-stealing suits many short CPU-bound tasks with idle cores to steal from; under a controlled cpuset it adds contention and gives zero isolation between cluster liveness and creature load. Add a `cluster-dispatcher` (`thread-pool-executor`, `fixed-pool-size = 2`) assigned to `SimulationManager`, `Holder`, and `CollisionDetectorActor`, so handshakes/heartbeats/the Finish protocol never queue behind creature work — directly targets the observed livelock symptom (manager 15s handshake timeout, `Up seen=false`) under CPU/GC pressure. Also give `collision-dispatcher` an explicit executor + size — today it configures only the priority mailbox and silently inherits default-dispatcher settings.
- **Env-tunable parallelism without rebuilds**: HOCON `${?DL2L_...}` substitution for the fork-join sizes (`component-dispatcher`, `object-dispatcher`, `default-dispatcher`) in `config/docker-config.conf`/`config/ccad-config.conf`; defaults preserve today's values.
- **Native torch threads**: `wm-dispatcher` pins DJL inference to one actor thread, but libtorch's own intra-op pool sizes off visible cores; set `OMP_NUM_THREADS` (holder instance, CCAD) alongside `JAVA_CPUS`. (`ActiveProcessorCount` covers the JVM side; this covers the native side.)

### W6. `extract.py` consumes Arrow (same PR as the writer)

- `scripts/dl2l_data/extract.py`: delete `RAW_TABLES`; `_open_raw_views` globs `raw_dir/*.arrow` → `pa.ipc.open_stream(pa.memory_map(path)).read_all()` → `conn.register(...)` (DuckDB scans Arrow zero-copy) → `CREATE VIEW data.<t> AS ...`. Keep a missing-table warning against the tables the queries reference.
- `tables.py`'s 15 queries: **verbatim, untouched**. Output stays Parquet via `save()` — now with real dtypes end-to-end. Backup-size glob `*.parquet` → `*.arrow`. `pyarrow`/`duckdb` already provisioned on all three envs.

### PR 1 verification

1. `mvn package && mvn test` + new tests: `TableSchemas` golden parity vs old column lists; `ArrowIpcBackend` round-trip (all-null rows, batch boundary N/N+1, post-finalize drop, allocator-limit breach); queue counter; component buffering (threshold, postStop flush, N=0 passthrough).
2. Cross-language golden test: JUnit dumps fixed states per table → run `dl2l_data.extract` over it → assert the 15 Parquet outputs' columns/dtypes/values vs a checked-in expectation (the analysis-contract gate).
3. Canonical load repro: `20260717_memory_vs_wm_dense_no_reposition_1_baseline.conf` (10 creatures) full local run — queue depth ~0, RSS flat, arrow allocation < limit, 22 non-trivial `.arrow` files, extraction + analysis smoke.
4. Constrained rehearsal: same conf under docker `cpus: 6` + `JAVA_XMX=2g` (worst-case CCAD emulation) — expect clean pass or fast watchdog abort with marker, never livelock; repeat with the real target shape (`cpus: 14`, per-role `JAVA_CPUS` budgets, 6g).
5. Delete `ParquetBackend`/`TunedParquetWriter`/parquet-floor; re-run 1–3.
6. **Final gate**: CCAD `p79_ccad_baseline_validation` (2 trials) with new heap/`--mem` — both complete, data extracted and uploaded (submit / `-e rescue=true` collect pattern).

## PR 2 — read-path cleanup

1. `analysis/dl2l_analysis/loading.py`: retire `num()` coercions (documented shim for pre-Arrow datasets); gate by re-running `analysis/experiments/20260717_memory_vs_wm_dense_no_reposition.py` on PR-1 output and diffing aggregates.
2. `ml/scripts/prepare_dataset.py`: delete `_cast_numeric` + stale "extractor writes strings" comment; optional `--emit-arrow` (Feather v2) beside Parquet.
3. `ml/jepa/dataset.py`: optional `.arrow` fast path (pyarrow → zero-copy numpy → `torch.from_numpy`). `upload_hf.py` unchanged (uploads stay Parquet).
4. Flagged extra: dedup `creatures` birth+death rows in extract.py after the verbatim query (keep max deadtime per creature_key) — fixes inflated `manifest.json` `n_creatures`; this is the dedup #81 explicitly deferred.
5. Docs: postscript in `docs/plans/parquet-write-path.md`; new `docs/plans/arrow-ipc-write-path.md` (per dev-cycle rule, the implementation plan lands in `docs/plans/`); fix extract.py docstring.

## Risks

- arrow-java 18.x on JDK 23 / old shade 2.4.1 (allocator init, resource merge) — mitigated by constructor self-check + package smoke; fallbacks: `arrow-memory-netty`, shade bump.
- CCAD sizing (`6g`/`24G`/`14 CPUs` + per-role budgets) is a measured starting point — tune off the first job's numbers. Fewer trials per node (≤4 instead of up to 9) lengthens experiment wall-clock — explicitly accepted in favour of stability.
- `ActiveProcessorCount=1` on manager/idProvider is tight (slower JIT warmup, single GC worker) — they're near-idle roles, but bump to 2 if startup handshakes get sluggish.
- Watchdog false abort — 500k threshold vs 8k benign blips + hysteresis; worst case is a cheap loud abort of a trial that was invalid anyway.
- Buffering delays last-moment states — postStop flush + late-write counter make the residual window observable, strictly smaller than the silent-dead-writer window it replaces.
- Stream truncation on crash — readable to last complete batch by design; document the recovery one-liner.

## Key files

- `src/main/java/br/cefetmg/lsi/l2l/creature/bd/ParquetBackend.java` (schema source → `TableSchemas`, then deleted)
- `src/main/java/br/cefetmg/lsi/l2l/creature/bd/PersistenceExtension.java` (backend construction, watchdog + watcher)
- `src/main/java/br/cefetmg/lsi/l2l/creature/bd/BDActor.java` (tableFor delegation, try/catch markers)
- `src/main/java/br/cefetmg/lsi/l2l/creature/components/CreatureComponent.java` + `ComponentActor.java` (buffering, flush-on-stop)
- `src/main/java/br/cefetmg/lsi/l2l/common/ComponentMessageQueue.java` (O(1) counter)
- `scripts/run-dl2l.sh`, `scripts/dl2l_data/extract.py`
- `ansible/roles/trial_runner_{local,pi,ccad}` templates, `docker/docker-compose.yml`, `ansible/roles/common/templates/docker-compose.yml.j2`, `inventories/ccad/group_vars/all.yml`
