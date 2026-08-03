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
- **Writes — staged, validate before specializing.** `BDActor` keeps its `expand()` +
  dedup, `INSERT_SQL`/`UPSERT_TABLES`/`bind()` **unchanged**, still `PreparedStatement`
  + `addBatch()`/`executeBatch()` — just against an embedded DuckDB connection instead
  of a networked Postgres one. The `bd-dispatcher` config comment already establishes
  the actual bottleneck was raw write throughput, not batching/transaction overhead —
  and embedding removes the whole network round-trip + WAL/MVCC/lock machinery a
  client-server RDBMS pays on every batch regardless of batching.
  **CONFIRMED LIVE this hypothesis was wrong**: local validation (single creature,
  `p79_single_creature_diag.conf`, 5-minute cap) hung well past its `Finish` signal —
  a `jstack` thread dump caught `l2l-bd-dispatcher` stuck inside
  `DuckDBPreparedStatement.executeBatch()` with 76s of CPU time on one call.
  `PreparedStatement`/`executeBatch()` is evidently not what DuckDB is optimized for
  (it's the documented use case for the `Appender` API instead) — moved to `Appender`
  for the 19 non-upsert tables, `PreparedStatement`+`ON CONFLICT` kept only for the 3
  tables that need it (`creature_state`, `change_stimulus_state`, `stimulus_state` —
  `Appender` has no upsert semantics). Hypothesis: this
  alone raises the ceiling far enough that sustained backlog growth stops happening,
  without needing to touch the binding code at all. **Only reach for DuckDB's
  `Appender` API (columnar bulk-load, bypasses SQL parsing/binding entirely) as a
  follow-up if the local diagnostic (Verification) still shows backlog growth** — same
  "ship the simple thing, measure, specialize only if needed" pattern this codebase
  already used for COPY vs. batched INSERT. If/when adopted, sharding likely becomes
  unnecessary either way (a single embedded appender should comfortably outrun 10
  creatures' production). DuckDB is embedded in the sim JVM; the DB file lives under
  `saveDir` (node-local `/scratch` on CCAD, same locality as the postgres overlay
  today, or a temp path locally).
- **Split responsibility, refined after tracing `saveDir`/extraction more closely: the
  JVM only ever dumps raw tables; all denormalization stays in Python, unchanged.** At
  shutdown (existing Flush/drain path), `BDActor`/`Holder` runs one `COPY <table> TO
  '<dir>/<table>.parquet' (FORMAT PARQUET)` per raw entity table (~21, one line each,
  generated from the same table list `tableFor()` already switches on) — no joins, no
  business logic, in Java at all. `<dir>` is `saveDir`, the `--save` CLI flag already
  threaded from `Main.java` → `Holder` (currently dead code — nothing has consumed it
  since `DataAnalyser` was deleted). This needs a host-visible mount (Docker volume /
  Singularity bind) that doesn't exist today (checked: no `/data` mount in
  `docker-compose.yml`/`.j2` currently — extraction is 100% "exec into the live DB
  container").
- **`scripts/dl2l_data/extract.py` changes from "run SQL over a live Postgres via psql
  exec" to "run the exact same SQL (`tables.py`, effectively unchanged) over local
  Parquet files via an embedded DuckDB."** Register each raw `<table>.parquet` as a
  DuckDB view (`read_parquet(...)`), then run each of the 15 queries verbatim (minor
  Postgres→DuckDB dialect fixes expected, e.g. `::text` casts, no `data.` schema
  prefix), write results the same way `save()` already does. `psql_copy`/`pg_dump`/
  `--container`/`--runtime`/`--db-port` all go away — extraction no longer talks to any
  running process, just reads files already sitting on disk. This is a bigger
  simplification than running the joins inside the JVM (my first draft of this option):
  zero join logic duplicated into Java, `extract.py`'s structure/tests barely change,
  and it mirrors today's script shape most closely.
- **Raw backup for free.** The per-table raw Parquet dump *is* the backup — arguably
  better than `pg_dump` since it's already columnar/directly analyzable, no restore step
  needed.
- **`manifest.json`.** Unchanged shape; `n_creatures` now comes from counting the
  `creatures.parquet`/`creature_state.parquet` rows in the Python step instead of a SQL
  query.
- **CCAD/Docker plumbing this enables removing:** the Postgres `singularity instance`
  entirely (`run_trial.sh.j2`'s `pg_isready` loop, WAL-overlay sizing, `PG_PORT`,
  `DL2L_DB_URL`), `docker-compose.yml`'s `dl2l-db` service, `config/schema.sql`'s
  Postgres DDL (replaced by DuckDB `CREATE TABLE` issued from Java, or a DuckDB-dialect
  equivalent file). Add: a `saveDir` volume/bind mount in both `docker-compose.yml`(`.j2`)
  and `run_trial.sh.j2`, and `duckdb` added to CCAD's provisioned Python
  `--user`-site-packages (alongside the existing pandas/pyarrow).

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

**Original recommendation was B (embedded DuckDB)** for the reasons above. Superseded by
what actually happened: DuckDB's write path (both `PreparedStatement`/`executeBatch()` and
the `Appender` API) never got past ~150 states/sec against this workload in local testing,
and the root cause was never conclusively isolated (see "Local validation log" below) -
isolated benchmarking showed DuckDB's own APIs are individually fast, so something about
how `BDActor` drives them (many small repeated `PreparedStatement`/`Appender` cycles) is
implicated, not proven. Rather than keep chasing it, **both options were implemented as a
runtime-selectable `PersistenceBackend` strategy** (`DuckDBBackend`/`ParquetBackend`,
`PERSISTENCE_BACKEND` env var, default `duckdb`) - see "Strategy pattern" below.
**`ParquetBackend` (Option A) is the one confirmed working under real heavy load as of this
writing**; `DuckDBBackend` stays in the tree, selectable, but with its performance issue
unresolved.

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

## Local validation log (in progress) — a bigger finding than a write-path bug

**Attempt 1** (`PreparedStatement`/`executeBatch()` against embedded DuckDB, single
creature, `p79_single_creature_diag.conf`, 5-min cap): hung past `Finish`. `jstack` caught
`l2l-bd-dispatcher` stuck inside `DuckDBPreparedStatement.executeBatch()`, 76s of CPU time
on one call. Confirmed `executeBatch()` isn't viable against DuckDB for this workload —
see "Writes" above, now on the `Appender` API for the 19 non-upsert tables.

**Attempt 2** (Appender-based rewrite, same config): still OOM'd — `Xmx2g` heap pegged
(G1 doing back-to-back full compactions reclaiming almost nothing, ~2010-2047M used) by
**~2 minutes into a 5-minute run**, well before `Finish` was ever sent. Root-caused via
Prometheus's own scrape of `dl2l_bdactor_queue_depth`: **0 → 3.8M queued items in under
100 seconds** for a single creature, then flatlined (the JVM had stopped making progress).
That is a ~38K states/sec production rate from one, completely uncontended creature — no
CPU competition from other creatures (only one), no Postgres slowness left to incidentally
throttle anything anymore (that's the whole point of this pivot). `Fruit was eaten` events
confirm the creature stopped producing *new* work exactly at `Finish` — this wasn't runaway
post-shutdown activity, just an already-catastrophic backlog from the first ~2 minutes of
plain, single-creature operation.

**This isn't primarily a slow-writer bug anymore.** The Appender fix likely did raise the
write ceiling substantially (unverified in isolation, moot at this production rate), but no
realistic sink — DuckDB, Postgres, anything — sustains 38K states/sec from a single
creature indefinitely. This looks like the actual scenario issue #79's original framing
anticipated from the start ("test the easiest fix [Phase A: rescale metabolic constants]
... if it doesn't fix, then we try coupling metabolism to time itself [Phase B]"): with
every artificial throttle now removed from the write path, there may be **nothing pacing
the cognitive-cycle tick rate to wall-clock time at all** — it might simply run as fast as
the CPU/actor mailbox allows, uncapped, which Phase A's rescaled *rate constants* (hunger
drift per tick, etc.) don't address since they don't touch tick *frequency*. Not yet
confirmed against the code (would need to trace what actually drives `PartialAppraisal`'s
per-cycle scheduling — event-cascade off sensory input vs. a real timer) — flagged for the
user rather than unilaterally starting a Phase B implementation, since that's explicitly the
decision point they reserved for themselves at the start of this issue.

**Not yet done**: confirming whether this same explosive production rate reproduces with
multiple creatures / under real CPU contention (the CCAD OOM took 5-25 minutes wall-clock
with 10 creatures, not ~2 — contention may already be partially throttling this in a way a
single uncontended local creature doesn't experience), and whether the write path itself
(post-Appender-fix) is now fast enough to be a non-issue once the production-rate question
is resolved.

## Strategy pattern: `PersistenceBackend`, and `ParquetBackend` confirmed working

Per explicit user direction (given the DuckDB throughput mystery above): implemented Option
A (direct Parquet, no DB) as a genuine runtime-selectable alternative alongside DuckDB,
rather than replacing it outright.

**Design**: `PersistenceBackend` interface (`persistBatch`/`flush`/`dumpToParquet`/`close`);
`BDActor` now only owns the actor protocol + backend-agnostic `expand()`/`tableFor()`
(object-graph expansion, table-name grouping), delegating actual writes to whichever backend
`PersistenceExtension` constructs. `DuckDBBackend` = the existing embedded-DuckDB code,
moved verbatim. `ParquetBackend` = new: one `blue.strategic.parquet` `ParquetWriter` per
table (Hadoop-free wrapper over `parquet-column`/`parquet-hadoop`), opened once, held for
the actor's lifetime. Selected via `PERSISTENCE_BACKEND` env var (`duckdb` default,
`parquet` to opt in) - set on `dl2l-holder` only (the only role that ever touches
persistence). No upsert semantics for Parquet (`creature_state`/`change_stimulus_state`/
`stimulus_state` land as duplicate rows instead of collapsing) - explicitly deferred to a
later extraction-side dedup, per the user's own scoping ("validate the write path only").

**Two real bugs found and fixed validating this against the exact same heavy-load repro
that broke DuckDB** (`p79_single_creature_diag.conf`, single uncontended creature):

1. **Silent total failure, first attempt.** `blue.strategic.parquet`'s `ValueWriter.write(name,
   null)` throws `NullPointerException` for numeric columns (`Cannot invoke
   "java.lang.Long.longValue()" because "value" is null`) - unlike JDBC `setNull`/DuckDB's
   `appendNull()`, this API represents a null OPTIONAL field by never calling `write()` for
   that column on that row (Parquet's definition-level tracking marks it absent), not by
   passing an explicit null through. This crashed `BDActor` on the very first batch (~10s
   into the run); `StoppingSupervisorStrategy` stops rather than restarts the actor, so
   every subsequent `persist()` for the rest of the run silently went to a dead actor -
   **zero visible error** until the run finished and the output Parquet files turned out to
   all have valid schemas but 0 rows. Also explains why the run then hung for hours at
   shutdown: `Holder.handleFinish()`'s own `Flush` ask against the already-dead `bdActor`
   threw, so `handleFinish()` never reached `system().terminate()` - the "cluster gossip
   stuck reconnecting to dead peers" symptom was a downstream consequence of this, not a
   separate bug. Fixed: `writeIfPresent()` helper skips the `write()` call entirely for null
   values; used everywhere a value can be null (every UUID reference, every nullable
   enum/String field).
2. **Second crash, natural-death path.** With (1) fixed, a second, different NPE surfaced
   (`org.apache.parquet.column.values.dictionary.IntList.add(int) ... encodedValues is
   null`) - a "used-after-close" shape of bug. Root cause: `flush()` originally closed
   (finalized) every writer, on the assumption `Flush` always immediately precedes
   `DumpParquet` (true for `Holder.handleFinish()`'s pair) - but `Holder.handleRemoveObject()`
   sends its OWN `Flush` the moment the *last creature dies*, strictly BEFORE
   `handleFinish()`'s later `Flush`+`DumpParquet`. For a short-lived diagnostic run the
   creature reliably dies from hunger (~150s, matching the Phase A target) well before the
   trial's `maxRuntimeMinutes` cap, so this path fires routinely, not as an edge case.
   Closing every writer on that first `Flush` finalized files that a later write (e.g. a
   trailing stat write) then crashed trying to write into. Fixed: `flush()` is now a
   deliberate no-op - `Flush`'s actual contract ("everything queued before it was already
   processed by the time it's acked") is already satisfied by mailbox FIFO ordering alone,
   since `ParquetWriter.write()` is a synchronous in-memory append; only `dumpToParquet()`
   (called exactly once, genuinely last) finalizes files.

**Third attempt, both fixes applied: clean success.** Same repro, creature died naturally
from hunger at ~150s (`All creatures dead in holder 0`), all three containers exited `(0)`,
zero exceptions in the logs. Real row counts in the raw Parquet output:
`stimulus_state` 4,825,567 rows, `change_stimulus_state` 2,099,262, `object_smelt_state`
600,452, `neuromodulator_state_log` 529,715, `endocrine_state_log` 287,325,
`object_seen_state` 208,259 — confirming the ~38K states/sec production rate measured
earlier via Prometheus was real, and `ParquetBackend` absorbed it over the creature's full
natural lifespan with no backlog and no OOM (`creature_state`: 2 rows, birth+death - the
expected duplicate-row shape for the no-upsert design). `consolidation_*`/
`memory_trace_stat`/`nose_state` at 0 rows is plausible (this diagnostic config likely
doesn't enable memory consolidation), not investigated further - out of scope for this
write-path validation.

**Status**: `ParquetBackend` is the first version of this write path to survive this
specific heavy-load repro end-to-end. Not yet done: the CCAD re-validation
(`p79_ccad_baseline_validation`, multi-creature, real contention) and the output-contract
regression check against `tables.py`'s existing 15-query extraction (still Postgres/DuckDB-
shaped SQL - `ParquetBackend`'s raw dump uses the same table/column names, so this should
mostly just work once `dl2l_data/extract.py` is pointed at it, but unverified).

## Correction: ParquetBackend also OOMs at 10-creature scale

Immediately re-tested locally against `20260717_memory_vs_wm_dense_no_reposition_1_baseline.conf`
(the exact config that originally OOM'd on CCAD - 10 creatures, 1200x900, reposition=false,
`maxRuntimeMinutes=60`), `PERSISTENCE_BACKEND=parquet`. **OOM'd within ~5 minutes** - same
`Xmx2g` GC-thrashing signature as every prior crash (`Pause Full (G1 Compaction Pause)
2044M->2044M(2048M)`, reclaiming nothing, repeated). `dl2l_bdactor_queue_depth` before the
crash: `0 → 297K (30s) → 938K (30s) → 1.24M (peak) → 948K (plateaus as GC thrashing takes
over)` - the same explosive-growth-then-plateau shape seen in every earlier crash
(DuckDB/Appender, ParquetBackend/1-creature-pre-fix), just needing 10 concurrent creatures
instead of 1 to reach it this time, consistent with `ParquetBackend` being a *faster* writer
than DuckDB ever was (confirmed: cleanly absorbed one uncontended creature's full ~38K
states/sec) but not an *unbounded* one.

**This matters for how the whole issue #79 effort should be read.** No finite writer speed
resolves this alone - it only moves the threshold (how many concurrent creatures / how much
sustained load) at which backlog growth outpaces drain rate and fills the heap. The
`ParquetBackend` work was still worth doing (removed the DuckDB-specific mystery
slowdown, is a real, substantial write-path improvement, confirmed empirically faster than
DuckDB's write path in the 1-creature case) - but it is not, by itself, the fix for the
original CCAD crash at realistic creature counts. This is now stronger, more direct
evidence for what the "cognitive-cycle tick rate has no wall-clock pacing at all" finding
(above, and in docs/plans/issue-79-decouple-biological-clock.md's revised Phase B section)
already implied: the production side, not just the write side, needs bounding. Recommend
prioritizing the Phase B rate-cap design next - the write-path work here is in a good,
committed state to build on top of once cognition itself is bounded to a realistic rate.

## Second correction: row-group-size fix reduced one memory sink, didn't remove the OOM

Investigated *why* `writer.write()` doesn't hit disk immediately: Parquet's columnar format
requires buffering a full row group per column in memory before any of it can be physically
written, and `parquet-hadoop`'s `InternalParquetRecordWriter` only auto-flushes a completed
row group once it crosses an internal size threshold. Confirmed via javap against the actual
jar that `ParquetWriter.DEFAULT_BLOCK_SIZE = 134217728` (128MiB) - and confirmed via
`parquet-floor`'s sources that its public `ParquetWriter.writeFile()` factory never calls
`.withRowGroupSize()` on the (real, overridable) builder it wraps, so every one of
`ParquetBackend`'s 22 concurrently-open writers was silently buffering toward 128MiB before
its own flush - worst case ~2.75GB just from row-group buffering, on top of the mailbox
backlog above.

Fixed by adding `TunedParquetWriter` (`creature/bd/TunedParquetWriter.java`) - copies
parquet-floor's `SimpleWriteSupport` verbatim (that part was fine) but builds directly
against the public `org.apache.parquet.hadoop.ParquetWriter.Builder`, setting
`withRowGroupSize(4MiB)` - bounding worst-case simultaneous buffering to ~88MB across all 22
writers instead of ~2.75GB. `ParquetBackend` now uses this instead of `parquet-floor`'s own
`ParquetWriter`.

**Re-ran the same 10-creature `..._1_baseline.conf` test. Still OOM'd** - same
`Pause Full (G1 Compaction Pause) 2046M->2046M(2048M)`, reclaiming nothing, repeated
signature, this time at ~850s (~14min) in. RSS looked deceptively flat/stable for most of the
run (~2.4-2.45GiB, tracked live via `docker stats`) - this was misread in the moment as a
good sign; in hindsight the process was already pinned at its heap ceiling early on, not
"stable," and the eventual symptom was a livelock (CPU pegged ~870-900% on GC-thrashing
threads, JVM alive per `docker inspect` but unresponsive to Akka handshakes - manager log
showed a 15s handshake timeout and `Up seen=false`) rather than a clean crash. `queue_depth`
was already at 3.3M+ (higher than the previous crash's 1.24M peak) *before* the GC-thrashing
phase even started, confirming the mailbox backlog - not per-writer row-group buffering - is
the dominant, still-completely-unbounded memory sink. (The `HeapDumpOnOutOfMemoryError`
dump, `heapdumps/java_pid9.hprof`, was left 0 bytes - the container teardown raced the dump
write; `gc.log` survived and is conclusive on its own.)

**Conclusion unchanged in direction, stronger in confidence**: the row-group fix was correct
and worth keeping (real bug, real fix, removes a real - just not decisive - contributor), but
no amount of write-path tuning fixes this. Phase B (bounding the production rate itself at
the `CreatureActor` pacemaker) is required, not just recommended, before revisiting write-path
work again.
