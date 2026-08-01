# Replace Postgres with a direct-to-columnar write path (new issue, branched off the issue-79 JPA-removal work)

## Why

The issue-79 work removed JPA/EclipseLink and made a sharded `BDActor` write to
Postgres via batched `INSERT`. Validating it on CCAD (the real target env, not this Mac)
crashed: **both** `p79_ccad_baseline_validation` trials (10 creatures each, node c10) hit
`java.lang.OutOfMemoryError: Java heap space` and were killed by Akka's
`jvm-exit-on-fatal-error`, 5-25 min into a 60-min budget, with `dl2l_bdactor_queue_depth`
reading 0 just before each crash. Root cause: the `BDActor` mailbox backlog grows without
bound whenever sustained production outpaces the Postgres write rate. The existing
issue-77/#78 caps (`max-batch-size`, `max-states-per-batch`) only bound a single
transaction's size, not the resident backlog; nothing bounds the queue itself.

Two fixes were considered and **rejected by the user, correctly**:
- *Drop messages at a queue cap* — corrupts the statistics this pipeline exists to
  produce; unexplained gaps in per-creature lifetime data are unacceptable.
- *Block cognition until the writer catches up* — ties the simulation's dynamics to disk
  write speed, an implementation detail; changes what's being studied.

The real problem is the choice of sink. This is an **append-only, write-once /
read-once-much-later telemetry** workload that is *never queried during the run* and is
**already shipped downstream as Parquet**. A transactional row-store (WAL, MVCC, per-row
parse/plan/index) is the wrong tool and is itself the bottleneck. The fix is to write to
a **columnar / Parquet** sink that is 1-2 orders of magnitude faster for bulk append, so
in steady state the sink outruns production, backlog stays near zero, and neither dropping
nor throttling is ever needed — no data loss, no coupling of cognition to I/O.

## The contract any replacement must preserve

Data flow today: `BDActor` → Postgres (~21 normalized entity tables) →
`scripts/dl2l_data/extract.py` runs 15 SQL queries (`scripts/dl2l_data/tables.py`) → 15
`*.parquet` files → `analysis/dl2l_analysis` (pandas).

Exact output contract (must stay identical so the analysis library is untouched):

- Per `(condition, trial)`: directory `<data_dir>/<condition>/trial_N/` containing **15
  named Parquet files** — `creatures, actions, drives, behavioural_efficiency,
  body_states, perceptions, mouth_interactions, sleep_episodes, neuromodulators,
  endocrine, expectancy, engrams, consolidation_episodes, consolidation_batches,
  memory_traces` — each with **the SQL queries' aliased column names** (see `tables.py`),
  plus appended `condition` and `trial` columns (added by `extract.py:save()`).
- A root `manifest.json` (`scripts/dl2l_data/manifest.py`) recording `n_creatures` (=
  count of `creature_state` rows) etc.
- Optional `db_backup.sql.gz` per trial (a full relational snapshot — see "what we lose").
- Upload to HF (`scripts/dl2l_data/upload.py`) is unchanged.

### The 15 logical outputs are NOT the ~21 physical entity tables

9 of the 15 are trivial single-table projections. The other 6 are **denormalizing
JOINs** whose only job is to pull `creature_key` (`css.key`) and `time` (`css.time`) from
the parent `ChangeStimulusState` down onto each child row (`body_states`, `perceptions`,
`mouth_interactions`, `actions`, `behavioural_efficiency`), and for `drives`, to flatten
the two `EmotionalState` snapshots off `InternalDynamicState`. **This denormalization is
the only real work Postgres does**, and `BDActor` already holds the whole object graph at
write time (every child references its parent — `bs.getChangeStimulusState()`,
`ids.getInitialEmotionalState()`, etc.; this is exactly what `BDActor.expand()` already
walks). So the joins are reproducible either in-process (Java, at write time) or as a
local pandas/SQL post-step — no information is lost by dropping Postgres.

## What the current (uncommitted) issue-79 work reuses vs. replaces

**Reused by BOTH options (keep as-is):**
- Entity POJOs in `creature/bd/` as the in-memory carriers of state (the messages
  `BDActor` receives). *(UUID ids: still needed by Option B as join keys; vestigial in
  Option A — see there.)*
- Deletion of the `analysis/extractor/*` package, `Main.java --extractor`,
  `persistence.xml`; `SequentialId` de-annotation — JPA stays gone.
- Routing of `CreatureActor` birth/death + `MemoryConsolidator`/`MemoryTraceConsolidator`
  writes through the single `BDActor` sink (`bdActorFor(...)`).
- `BDActor.expand()` (cascade walk) + identity-dedup — still needed to enumerate every
  row to write from a batch.
- `Constants.java` clock rescale, `Holder` Flush-drain protocol, `Sync.askAll` — all
  orthogonal, unchanged.

**Replaced / removed by both:**
- `pom.xml`: the `org.postgresql` JDBC driver.
- `config/schema.sql`, the Postgres service + `postgres:17` image + `init-db.sql` mounts
  in `docker/docker-compose.yml` and `ansible/roles/common/templates/docker-compose.yml.j2`.
- `BDActor`'s `writeTable`/`INSERT_SQL`/`UPSERT_TABLES`/`bind` and
  `PersistenceExtension.openConnection()` (the JDBC-to-Postgres bits).
- On CCAD (`ansible/roles/trial_runner_ccad/templates/run_trial.sh.j2`): the entire
  postgres `singularity instance`, its `pg_isready` readiness loops, the WAL-overlay
  sizing, `synchronous_commit=off`, `DL2L_DB_URL` plumbing, and the `singularity exec
  psql` extraction step. **This is the single biggest operational win** — ~half of
  `run_trial.sh.j2`'s hard-won workarounds are Postgres-specific and simply disappear.

---

## Option A — `BDActor` writes Parquet directly

`BDActor` accumulates rows **per logical output table**, denormalizing at write time, and
flushes Parquet row-groups. Each shard writes its own part-files; the analysis reads a
directory of part-files (standard Parquet, like Spark/Hive output — pandas/pyarrow read a
glob natively).

### Mechanics
- **Denormalize in Java at write time.** For each entity `BDActor` would persist, emit a
  row into the correct *logical* output buffer with parent-derived columns already
  flattened on: e.g. a `BodyState` → a `body_states` row carrying
  `css.getKey()`/`css.getTime()` read off its referenced `ChangeStimulusState`. The 6
  join queries become ~6 small Java row-mappers; the 9 trivial ones are direct field
  copies. UUIDs become vestigial (no FK to resolve) and can be dropped from the entities.
- **Row-group buffering = the batching we already have.** Keep N rows per table in a
  columnar buffer; flush a row-group when it hits a threshold and once more at trial end.
  Flushing is a fast sequential append, so backlog can't build the way it did against
  slow INSERTs. Memory is bounded by row-group size, not by total run volume.
- **`creature_state`/`creatures`.** Born-then-dead is the only "update" today. Simplest:
  hold the ≤N creature rows in memory and write each once at death (tiny), or emit
  born/dead as two rows and take `max(deadtime)` in the `creatures` projection.
- **Sharding.** Each of the `SHARD_COUNT` shards writes `body_states.shard0.parquet`,
  `…shard1.parquet`, … The analysis (or a trivial concat) reads all shards for a table.
- **`condition`/`trial` columns.** Passed in as sim config/env, written as constant
  columns (as `extract.py` does today).
- **Java Parquet library.** Mainstream `org.apache.parquet:parquet-*` drags in Hadoop
  (heavy, fat-jar bloat). Lighter paths to evaluate: `parquet-floor` (thin, Hadoop-free),
  or Apache Arrow Java → Arrow IPC/Feather (pandas reads Feather) if Parquet-in-Java
  proves painful. Library choice is the main open risk of this option.

### Trade-offs
- **+** Leanest end state: no DB in the write path at all, fastest possible sink, no
  post-step, no extra process.
- **−** The 15 queries' join logic becomes hand-written Java that must be proven correct
  row-by-row against the current SQL output (regression risk lives here).
- **−** New Java Parquet dependency to vet; `parquet-mr`/Hadoop is a heavy transitive tree
  in the fat jar.
- **−** Losing the raw normalized tables unless we *also* dump them (the `pg_dump` backup
  covered `change_stimulus_state`/`stimulus_state` etc. that the 15 queries don't surface).

---

## Option B — embedded DuckDB in-process (recommended)

Replace Postgres with **embedded DuckDB** (one JDBC dependency, no server, no container,
in-process). `BDActor` bulk-inserts raw entity rows via DuckDB's `Appender`; at trial end,
run the **existing `tables.py` denormalizing SQL almost verbatim** via
`COPY (SELECT …) TO 'table.parquet' (FORMAT PARQUET)`.

### Mechanics
- **Schema.** A DuckDB DDL close to `config/schema.sql` (same ~21 tables/columns). UUID
  ids stay as the join keys the SQL relies on (`…_id = css.id`).
- **Writes.** `BDActor` keeps its `expand()` + dedup, then appends each entity to its
  DuckDB table via the `Appender` API (columnar, buffered — far faster than Postgres
  INSERT; a single appender likely outpaces production, so **sharding may become
  unnecessary** — a real simplification to validate). DuckDB is embedded in the sim JVM;
  the DB file lives on node-local `/scratch` on CCAD (same locality as the postgres
  overlay today), or a temp file locally.
- **Extraction becomes in-process.** At shutdown (in the existing Flush/drain path), for
  each of the 15 logical tables run `COPY (<tables.py SQL, + condition/trial constants>)
  TO '<trial_dir>/<table>.parquet' (FORMAT PARQUET)`. No psql, no network, no
  `singularity exec`. The 15 queries move from `tables.py` into a resource SQL file (or
  stay in `tables.py` and are read by a tiny in-JVM runner) — either way the **SQL itself
  is reused, not rewritten**, so the analysis contract is preserved almost exactly.
- **Raw backup for free.** `COPY <each raw table> TO parquet` (or DuckDB's `EXPORT
  DATABASE`) preserves the full normalized dataset as the backup replacement, if wanted.
- **`manifest.json`.** Emitted from the same shutdown step (`n_creatures` = `SELECT
  count(*) FROM creature_state`).

### Trade-offs
- **+** Same throughput win + same operational cleanup as A (no daemon/container/
  network/readiness races).
- **+** Denormalization stays **declarative SQL** → lowest migration risk; the 15-column
  contract is preserved query-for-query, not reimplemented imperatively.
- **+** Purpose-built for exactly this (columnar bulk ingest → Parquet); keeps ad-hoc SQL
  ability; raw-table backup is free.
- **+** Likely removes the need for sharding (single fast appender), simplifying
  `PersistenceExtension`.
- **−** Still "a database" in-process (though embedded, file/in-memory, no server) — one
  new dependency (`org.duckdb:duckdb_jdbc`), and DuckDB's single-process write-concurrency
  model must be respected (serialize appends, or one appender total).
- **−** Team unfamiliarity with DuckDB (noted by the user).

---

## Comparison & recommendation

| | A: direct Parquet | B: embedded DuckDB |
|---|---|---|
| Throughput win | ✅ | ✅ |
| Removes Postgres container/daemon/network | ✅ | ✅ |
| CCAD `run_trial.sh` simplification | ✅ | ✅ |
| Denormalization | hand-written Java (regression risk) | reused SQL (low risk) |
| New dependency | Java Parquet lib (Hadoop-heavy or vet a thin one) | `duckdb_jdbc` (one jar) |
| Raw-table backup | extra work | free |
| Sharding still needed | yes (part-files) | probably not |
| End-state leanness | leanest (no DB at all) | very lean (embedded DB) |

**Recommendation: B (embedded DuckDB).** Same performance and operational wins as A, but
keeps the join logic in reused SQL rather than reimplementing 15 queries in Java, so it's
materially lower-risk to land correctly and preserves the analysis's Parquet contract
almost verbatim. The user has chosen B while asking that A stay documented so we can pivot
if DuckDB's in-process constraints or unfamiliarity bite. **This doc keeps both live.**

## What we lose either way (accepted)
- **`pg_dump` relational backup** — replaced by raw-table Parquet (free in B; extra step
  in A) if we want it at all.
- **Ad-hoc SQL over a live server** — the analysis is codified, not ad-hoc; and Parquet is
  directly queryable by DuckDB/pandas afterward regardless.
- **Crash durability mid-run** — *not actually relied on today*: CCAD's Postgres overlay
  is already ephemeral (node-local, discarded); durability comes only from the final
  extract-to-NFS copy, and a partial trial is scientifically invalid anyway. Same
  "finalize at end" model.

## Scope / branching
- New issue (file it), fresh plan (this doc). Branch `claude/parquet-write-path`, cut from
  the current issue-79 working state (JPA-removal + clock rescale), so all the reusable
  work above carries forward.
- **Recommend committing the current issue-79 JPA-removal + clock work as a checkpoint
  first** — it's a large (~80-file) uncommitted pile spanning two efforts now; a commit
  boundary makes this pivot diffable/recoverable before the DuckDB surgery rewrites
  `BDActor`/`PersistenceExtension` again. (Feature-branch checkpoint, not a merge to main.)

## Verification (for the chosen option)
1. `mvn package`/`mvn test` clean.
2. Local repro (`20260717_memory_vs_wm_dense_no_reposition_1_baseline.conf`, 10 creatures,
   the exact config that OOM'd on CCAD): full 60-min run, JVM heap/RSS stays flat, backlog
   near zero throughout, no OOM.
3. **Output-contract regression check**: extract one trial the old (Postgres) way and the
   new way from the *same* simulation seed/data, diff the 15 Parquet files
   column-for-column (row counts, dtypes, values). This is the gate that proves no
   analysis breakage.
4. Re-run `p79_ccad_baseline_validation` (2 trials) on CCAD: both survive the full budget,
   no OOM, data extracted and uploaded.
