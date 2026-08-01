# Remove JPA/EclipseLink from DL2L's persistence layer entirely

## Context

Validating issue #79's Phase A fix (rescaling `Constants.java` so creatures live ~150s
again at the ~4.3x higher post-#76/#78 cognitive-cycle rate) exposed that `BDActor` —
the single-threaded actor that persists all creature state to Postgres via JPA/EclipseLink
— cannot sustain the required write volume. A 3-creature test showed the holder pegged at
865% CPU, `BDActor`'s queue frozen at 5.6M+ backlogged states with zero progress over 15s,
and even the *live* creatures' cognitive-cycle counters crawling at ~0.5 Hz instead of
~220 Hz — a CPU-starvation spiral, not just slowness. An isolated (all-creatures-dead)
measurement had shown BDActor could only sustain ~17-18K states/sec, and that ceiling
collapsed further under concurrent contention from live creature actors.

Removing the now-unused `DataAnalyser` JPA-read pipeline from `Holder`'s per-trial death
path (already done — see below) was a first easy win, but the user has stated a
long-standing intent to remove JPA from the write path entirely: **data extraction is now
100% Python-side** (`scripts/dl2l_data/extract.py`, direct `psql`/`pg_dump`), so nothing in
the current pipeline needs JPA's read capability either. The user has also clarified: the
`--extractor` standalone CLI mode is **deprecated and should be deleted**, removing the
last reason to keep any JPA machinery around, and the replacement write layer should be
designed for maximum throughput ("blazingly fast, super optimized"), not just "no longer
JPA."

**Already done (this branch, prior to this plan):**
- `Holder.handleRemoveObject()` no longer runs `DataAnalyser`/`RoutineCreator` on every
  trial's death (removed ~15 lines + 3 imports) — that JPA-read pipeline was entirely
  unconsumed by the current pipeline and was pure CPU/connection contention with BDActor.
- `Holder`'s two `Sync.ask(bdActor, Flush, ...)` timeouts raised 30s → 1800s (`FLUSH_TIMEOUT_SECONDS`),
  fixing the *separate* bug where a slow-but-eventually-successful drain threw before
  `AllCreaturesDead` could ever reach `SimulationManager`, hanging the holder forever.
  This stays regardless of the JPA removal — it's still a reasonable backstop.

This plan covers the **larger removal**: BDActor's actual write mechanism, plus every
other JPA touchpoint in the app.

## Full entity catalog (from exploration)

22 `@Entity` classes + `SequentialId` (`@Embeddable`), all in `creature/bd/`, all listed in
`persistence.xml`, all `@Id @GeneratedValue` (AUTO strategy, Postgres-sequence-backed int).

**Written via `BDActor`** (the hot path — `CreatureComponent.persist(PersistenceState...)`
→ `bdRef.tell(states)` → `ComponentMessageQueue` batches into one transaction):
`ChangeStimulusState` (parent, owns `receivedStimuli`/`emittedStimuli` `List<StimulusState>`
via `@OneToMany(cascade=ALL, mappedBy=...)` — **children are never separate array elements,
only cascade-reachable**), `StimulusState` (owning `@ManyToOne` FK to
`changeStimulusEmitted`/`changeStimulusReceived`), `EyeState`, `ObjectSeenState`,
`BodyState`, `MouthInteractionState`, `ObjectSmeltState`, `ChosenActionState`,
`BehaviouralEfficiencyState`, `RegulationBatchStat`, `InternalDynamicState` (cascades
**two** `EmotionalState` snapshots that are *also* never separate array elements — pure
cascade reachability, same pattern as ChangeStimulusState's children), `EmotionalState`,
`EngramState`, `ExpectancyState`, `SleepEpisodeState`, `NeuromodulatorStateLog`,
`EndocrineStateLog`.

**Written via separate, standalone single-row JPA writers (not through BDActor):**
- `CreatureState` — `CreatureActor.java` (own `EntityManager`, sync write at birth + death).
- `ConsolidationEpisodeStat` + `ConsolidationBatchStat` — `MemoryConsolidator.java` (own
  `EntityManager`, no relationship between them, correlated only by `creature_key`+`onset_cycle`).
- `MemoryTraceStat` — `MemoryTraceConsolidator.java` (own `EntityManager`, standalone).

**Dead — never constructed anywhere in `src/main/java`, and their tables are read nowhere
in `scripts/dl2l_data`/`analysis`/`ansible` (confirmed by grep):** `MouthState`,
`MemoryEvocationState`. Drop entirely, don't port.

**The one real data-format gotcha:** `ObjectSeenState.type` and `ObjectSmeltState.objectType`
are typed `WorldObjectType` — an *interface* (implemented by enums `FruitType`/`PlantType`
and a plain class `Self`), not an enum, so JPA falls back to `@Lob` Java-serialization (a
BLOB). Python's `scripts/dl2l_data/db.py:decode_type_hex()` already has to hex-decode that
blob and substring-search for known type names — a fragile hack. Replacing this column
with a plain string (e.g. the type's `.name()`/identifying string) removes the Java
serialization entirely and lets that Python function collapse to a passthrough.

## Design

**1. UUID primary keys, client-assigned, no DB round-trip.** Every entity gets
`private final UUID id = UUID.randomUUID();` in place of `@Id @GeneratedValue int id`. This
is the crux simplification: every FK reference becomes "read the referenced object's
already-known `.getId()`" — no `RETURNING id`, no per-row round trip, and **no
insert-ordering constraint** at all, since a row's FK value is known before any row is
written. Combined with a plain schema (no enforced FK constraints — this is an append-only
telemetry log, not a transactional system; referential integrity comes from the app always
generating consistent UUIDs, not from the DB), insert order becomes fully irrelevant and
every table's rows can be written in one shot in any order.

**2. Batched `INSERT ... ON CONFLICT (id) DO NOTHING`, not `COPY`.** Originally planned as
`COPY` for maximum throughput, but `CreatureComponent.persist()`'s own javadoc documents a
**confirmed live bug** from the JPA era: the same `ChangeStimulusState` instance is
sometimes referenced across *two separate* `persist()` calls, not bundled into one (e.g.
`Eye.java` calls `persist(change, seen)` then, separately, `persist(change, eyeState)` with
the identical `change` object) — under JPA this "worked" via merge-on-already-has-an-id
detection; under UUIDs, if those two calls land in *different* `BDActor` batches (no
ordering guarantee stops this), both would try to insert a row with the same id. `COPY` has
no conflict-handling clause, so a genuine duplicate would violate the primary key and crash
the batch. `ON CONFLICT (id) DO NOTHING` makes a duplicate insert attempt safe with zero extra application-side bookkeeping (no in-memory "already
written" set to maintain across BDActor's lifetime, which would otherwise grow unboundedly
over a long run). Refined further during implementation: `DO UPDATE SET <every column> =
EXCLUDED.<column>`, not `DO NOTHING` — `CreatureState` is written twice with the *same*
UUID and *different* values (`CreatureActor` persists it at birth, then again at death with
`deadTime` now set — see `CreatureActor.kill()`), so `DO NOTHING` would silently drop the
death-time update. `DO UPDATE` handles both cases correctly (a same-values "update" from the
duplicate-reference case above is a harmless no-op) since genuine conflicts are rare.
`PreparedStatement.addBatch()`/`executeBatch()` per table per transaction, with pgjdbc's
`reWriteBatchedInserts=true` connection parameter so the driver itself coalesces the batch
into efficient multi-row `INSERT` statements under the hood.

**Correction after live measurement:** applying `ON CONFLICT DO UPDATE` to *every* table
(not just the ones that need it) degrades throughput over time — Postgres's speculative-
insertion protocol for `ON CONFLICT` costs meaningfully more per row than a plain `INSERT`
even on the ~99.99% of rows that never actually conflict, and that tax compounds as tables
grow. A single long-lived creature's cognitive-cycle rate crashed from ~1400 Hz to ~80 Hz
over a few minutes, with `BDActor`'s backlog growing unboundedly, before this was caught.
Fixed by restricting the upsert path to exactly the three tables that can legitimately
receive a repeated id (`creature_state`, `change_stimulus_state`, `stimulus_state` — see
`BDActor.UPSERT_TABLES`'s javadoc for the full reasoning per table) and using plain `INSERT`
(no `ON CONFLICT` clause at all) for the other ~18, which carry the large majority of write
volume.

**3. `BDActor`'s per-batch algorithm** (replacing `em.persist()` in a JPA transaction):
   1. Take the already-flattened `List<PersistenceState>` `ComponentMessageQueue` hands it
      (unchanged — batching/capping logic in `ComponentMessageQueue`/`bd-dispatcher` config
      stays as-is).
   2. **Expand**: for the two cascade-only-reachable cases (`ChangeStimulusState`'s
      `receivedStimuli`/`emittedStimuli`, `InternalDynamicState`'s two `EmotionalState`
      snapshots), walk and add those nested objects to the working set. Every other
      relationship in the catalog above is *already* a separate top-level array element at
      the call site (confirmed per-call-site in exploration) — no expansion needed for those,
      just read `.getId()` off the referenced object directly.
   3. **Dedup by object identity** (`IdentityHashMap`-backed set) — the same object can
      appear both as a top-level array element and nested inside another entity (e.g.
      `PartialAppraisal.persistCycle`'s `changeEmotional` is both standalone and referenced
      from `behaviouralState`); must write it exactly once.
   4. Group the deduped working set by target table, batch one `INSERT ... ON CONFLICT (id)
      DO NOTHING` per row via `PreparedStatement.addBatch()`, `executeBatch()` once per
      table, all inside one JDBC transaction (mirrors today's one-transaction-per-dequeue
      semantics). The identity dedup above avoids most redundant attempts within a batch;
      `ON CONFLICT` is the safety net for the cross-batch case dedup can't see (§2).
   5. `Flush` handling (the FIFO-ordering-guarantee trick already in `BDActor.onReceive`)
      stays conceptually the same — just acks once no real work precedes it in the batch.

**4. Schema**: hand-authored `schema.sql` (function as the new `config/init-db.sql`, or a
sibling file also mounted into `/docker-entrypoint-initdb.d/`) replacing EclipseLink's
`drop-and-create-tables`. `uuid` columns for every id/FK (no `DEFAULT gen_random_uuid()`
needed — always app-supplied), no `FOREIGN KEY` constraints (see point 1), same table/column
*names* Python's `scripts/dl2l_data/tables.py` already queries (only id/FK column *types*
change from `int4`→`uuid`, plus `object_type`/`type` becoming plain `text` instead of
`bytea`). Drop `mouth_state`/`memory_evocation_state` tables entirely. Author this by
generating EclipseLink's DDL once (temporarily, via
`eclipselink.ddl-generation.output-mode=sql-script`) as a starting point, then hand-edit
column types/constraints — avoids hand-transcribing ~20 tables from scratch.

**5a. Upgrade the Postgres JDBC driver and container image.** `pom.xml` currently pins
`org.postgresql:postgresql:9.3-1102-jdbc4` — a driver from the pgjdbc project's old
pre-semver scheme, over a decade old, predating years of pgjdbc's own COPY/batch/binary-
protocol improvements. Bump to `42.7.13` (latest on Maven Central, verified against
`repo1.maven.org`). The container image (`image: postgres` in
`ansible/roles/common/templates/docker-compose.yml.j2` and the tracked
`docker/docker-compose.yml`, unpinned) currently resolves to Postgres 17.5 (verified
locally) — already reasonably current, but pin it explicitly (`postgres:17`) for
reproducibility rather than silently floating on `:latest`. (The three untracked
`docker/docker-compose-*.yml`/`.bak` files in the working tree are the user's own scratch
configs, not part of the maintained pipeline — leave untouched.)

**5b. Connection management: route every write through `BDActor` — no pool at all.**
Revised from the original draft (which proposed a HikariCP-backed pool for the low-volume
writers): the user pointed out that's unnecessary complexity — `BDActor` should be the
**sole** owner of a JDBC connection in the whole JVM, and everything that currently opens
its own `EntityManager` (`CreatureActor`'s birth/death `CreatureState` writes,
`MemoryConsolidator`'s episode+batches, `MemoryTraceConsolidator`'s stat) instead just
`tell()`s `BDActor` the same way `CreatureComponent.persist()` already does. This is both
simpler (zero new dependency — plain `DriverManager.getConnection()` once in `BDActor`'s
constructor, reused for its whole lifetime) and more idiomatic for this codebase (matches
CLAUDE.md's Akka anti-pattern guidance: components communicate through message passing, not
by each independently touching a shared external resource) and structurally forecloses any
repeat of the "~70-100 separate connections" incident `PersistenceExtension.java`'s history
documents. `PersistenceExtension` shrinks to just exposing the shared `bdActor` ref (as it
already mostly does) — no `DataSource`/pool abstraction needed anywhere. These three
writers' entities (`CreatureState`, `ConsolidationEpisodeStat`, `ConsolidationBatchStat`,
`MemoryTraceStat`) have no relationships (per the catalog above), so they slot into
`BDActor`'s existing group-by-table batching with no special-casing. Preserve the exact
`DL2L_DB_URL` env-var override behavior already in `PersistenceExtension.Impl.jdbcUrlOverride()`
(needed for CCAD's Singularity networking) in whatever builds `BDActor`'s connection string.
Consider `synchronous_commit = off` for this connection — telemetry data, loss-tolerant,
removes fsync-per-commit latency; flag as a candidate, verify empirically.

**6. Delete entirely**: `analysis/extractor/` package (~20 classes), `DataAnalyser.java`,
`RoutineCreator.java`, `Main.java`'s `--extractor` CLI option + `runExtractor()`,
`persistence.xml`, the EclipseLink/`javax.persistence` Maven dependencies in `pom.xml`. Every
remaining entity class in `creature/bd/` loses its `javax.persistence.*` imports/annotations
(becomes a plain POJO with a `UUID id` field); `SequentialId` loses `@Embeddable`.

**7. Python-side**: simplify `scripts/dl2l_data/db.py:decode_type_hex()` /
`tables.py:_decode_object_type()` to a passthrough (or delete the post-processing hook
entirely) now that `object_type`/`type` columns are written as plain text.

**8. Sharded BDActor pool (post-implementation follow-up).** Validating Phase A's
recalibrated `S` (against the *new*, JPA-free throughput) exposed that a single BDActor's
sustained ceiling (~18-20K states/sec, confirmed empirically, holds even after the
targeted-upsert fix) is below the *sustained* generation rate of a few concurrently-alive,
long-lived creatures once lifespans reached their intended ~150s — not just a death-tail
problem this time: 3 creatures alive together crashed the pooled cognitive-cycle rate from
~900 Hz to ~33 Hz with the backlog growing throughout the run, not just at the end.
`PersistenceExtension` now holds a small pool of `BDActor`s (`SHARD_COUNT = 4`, a starting
point not a tuned optimum), each with its own JDBC connection and `PinnedDispatcher` thread,
routed by `creatureKey % N` (`PersistenceExtension.bdActorFor(...)`) so every component of
one creature stays on the same shard (preserving BDActor's per-batch identity-dedup and
`expand()` ordering assumptions). Cross-shard writes to the same row (the
`change_stimulus_state`/`stimulus_state` duplicate-reference case) stay correct regardless,
since `ON CONFLICT DO UPDATE` already made same-row concurrent writes safe at the Postgres
level. `Holder`'s two Flush drain points now flush every shard in parallel
(`Sync.askAll`, `Holder.java`) before declaring a trial done.

## Files to touch

- New: `config/schema.sql` (or extend `init-db.sql`).
- Rewrite: `BDActor.java` (core of this change — owns the one JDBC `Connection`, batched-INSERT
  batch writer, table-mapper dispatch), `PersistenceExtension.java` (shrinks — no more
  `EntityManagerFactory`, just exposes the `bdActor` ref), every entity class in
  `creature/bd/` (strip `javax.persistence.*` annotations, add `UUID id`).
- Small edits: `CreatureActor.java`'s two write sites, `MemoryConsolidator.java`/
  `MemoryTraceConsolidator.java`'s write sites — swap their own `EntityManager`
  persist/commit for `PersistenceExtension.of(system).bdActor().tell(...)`, same pattern
  `CreatureComponent.persist()` already uses.
- Delete: `analysis/extractor/*.java`, `DataAnalyser.java`, `RoutineCreator.java`,
  `persistence.xml`; trim `Main.java` and `pom.xml`.
- Small Python fix: `scripts/dl2l_data/db.py`, `scripts/dl2l_data/tables.py`.

## Verification

1. `mvn package` clean (no leftover `javax.persistence` references anywhere).
2. Re-run the single-creature diagnostic (`simulations/p79_single_creature_diag.conf`,
   already created) — confirm no CPU-starvation spiral, cognitive-cycle rate stays near
   the ~666 Hz measured pre-JPA-removal baseline throughout the creature's full life, not
   just at the start.
3. Re-run the 3-creature `p79_metabolic_rescale` experiment end-to-end (previously killed
   for runaway resource use) — confirm all 3 trials complete, holder exits cleanly, and
   BDActor's queue depth stays bounded/near-zero throughout rather than growing unbounded.
4. Spot-check `scripts/dl2l_data.extract` on the new schema produces Parquet output
   matching the expected `tables.py` column shapes (especially `object_type` no longer
   needing hex-decoding) — compare a small trial's extracted Parquet against the current
   (JPA-era) format for the same columns.
5. Confirm `mouth_state`/`memory_evocation_state` tables' absence doesn't break anything
   (already verified nothing reads them).
