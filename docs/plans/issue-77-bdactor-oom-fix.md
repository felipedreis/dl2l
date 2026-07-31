# Fix BDActor OOM under sustained load (issue #77)

## Context

Under sustained real load (10 creatures, `20260717_memory_vs_wm_dense_no_reposition_1_baseline.conf`,
UI on, several minutes), the holder JVM reliably OOMs and crashes. A prior investigation (5 comments
on [issue #77](https://github.com/felipedreis/dl2l/issues/77)) traced this to **two independent,
confirmed root causes**, both introduced/exposed by PR #76's async-persistence rework:

1. **Blocking `creature.bd()` accessor called on every `persist()`.** `Creature` is an Akka
   `TypedActor` proxy — every non-void method (`bd()` included) is a blocking `Await.result()`
   round-trip. `CreatureComponent.persist()` calls `creature.bd().tell(states)` fresh on every
   invocation, ~14 call sites firing every cognitive cycle. Live thread dumps show
   `component-dispatcher` (fork-join, `parallelism-max=6`) spawning 86+ actual threads — Akka's
   blocking-compensation mechanism absorbing this — which starves other dispatchers and compounds
   the crash. `bd()`'s underlying value never changes for a creature's lifetime, so it's a clean
   candidate for one-time caching instead of repeated blocking resolution.

2. **BDActor's unbounded per-transaction batch (the dominant driver — confirmed via Eclipse MAT
   heap-dump analysis, ~91% of retained heap in 4 leak suspects, all tracing back to BDActor).**
   `ComponentMessageQueue.dequeue()` drains the *entire* mailbox backlog into one `List` per poll,
   handed to `BDActor.onReceive` as a single `begin()`/loop-`persist()`/`commit()` transaction. Under
   sustained overload this is a runaway feedback loop: bigger backlog → bigger batch → bigger
   transaction → EclipseLink's `RepeatableWriteUnitOfWork` (L1 identity map — L2 cache is already
   disabled via `persistence.xml`'s `eclipselink.cache.shared.default=false`, confirming L1 as the
   real culprit) holds every entity in that one transaction simultaneously until commit. The heap
   dump caught ~965K entities' identity-map entries (785MB) still mid-`persist()`-loop, before
   commit ever ran. This was a deliberate, documented design choice
   (`docs/plans/bdactor-async-persistence-with-drain.md` §5 explicitly deferred bounding the batch,
   framing "batch-on-backlog" as free self-adaptive backpressure) whose own stated revisit-trigger
   ("a future experiment's metrics show sustained, unbounded queue growth") has now fired.

The user additionally suspected JPA/EclipseLink caching as a memory driver and proposed dropping JPA
for direct Postgres access. Investigation shows this is only partially right: L2 cache is already
off, and the actual L1 identity-map size is proportional to transaction/batch size — which the fix
below already bounds regardless of JPA vs. JDBC. `StimulusState`/`ChangeStimulusState` also have real
bidirectional `cascade=ALL` relationships, and ~24 files (`analysis/extractor/*`, `DataAnalyser.java`)
depend on JPQL named queries with relationship joins for the read/extraction path — so a full JPA
removal is a materially larger, higher-risk migration than the fix that actually addresses the
measured crash. **Per discussion with the user, this plan is scoped to the crash fix only; the
JPA→JDBC migration is deferred to a separate follow-up issue**, to be revisited once it's confirmed
whether this fix alone resolves the OOM.

Also worth noting for whoever picks up follow-on work: a third, independent backlog exists in the
heap dump (MAT Suspect 2, 23%, `akka.remote.default-remote-dispatcher`'s own mailbox, ~685K queued
messages) that is *not* obviously downstream of BDActor and isn't addressed here — the mini-experiment
below checks whether it shrinks as a side effect, and flags a follow-up issue if not. Separately, a
cycle-coupled biological clock (`PartialAppraisal.tickMetabolicPacemaker()`) is a compounding,
not-yet-decided cause per the issue's "Related" section — explicitly out of scope here too.

## Implementation

### 1. Cache the resolved `bd()` ref (root cause 1)

**`src/main/java/br/cefetmg/lsi/l2l/creature/components/CreatureComponent.java`**
- Add a field `private ComponentRef bdRef;`.
- In `init(Creature creature, ComponentRef selfRef, MetricsExtension.Impl metricsExt)` (lines
  53-62), right after `this.creature = creature;`, resolve once: `this.bdRef = creature.bd();`.
  This runs once per component, before any message delivery — the same lifecycle guarantee already
  relied on for `creature`/`selfRef`.
- Change `persist()` (lines 96-100) to call `bdRef.tell(states)` instead of `creature.bd().tell(states)`.

No other call site changes — all subclasses (`Eye`, `Nose`, `Mouth`, `Body`, `SensoryCortex`,
`EffectorCortex`, `PartialAppraisal`, `FullAppraisal`, `HomeostaticRegulation`, `Valuation`,
`NeuromodulatorSystem`, `EndocrineSystem`) call the inherited `persist(...)` unchanged. This
deliberately does **not** touch the other pre-existing blocking accessors (`eye()`, `holder()`,
etc.) — that's a larger, separate architectural question the investigation flagged but didn't
resolve; `bd()` is uniquely hot (added by #76, called on every persist) and trivially safe to cache
(single immutable value, no coordination needed), unlike accessors whose target could plausibly
change.

### 2. Bound BDActor's per-transaction batch size (root cause 2)

**`src/main/java/br/cefetmg/lsi/l2l/common/ComponentMessageQueue.java`**
- Add a constructor `ComponentMessageQueue(int maxEnvelopesPerBatch)`; the existing no-arg
  constructor delegates to it with `Integer.MAX_VALUE` (preserves `component-dispatcher`'s current
  unbounded behavior with zero config changes there).
- In `dequeue()`'s `while` loop, track a counter of **top-level envelopes merged so far** (an entire
  `PersistenceState[]` array counts as one envelope). Before each `queue.poll()` in the
  `Stimulus`/`PersistenceState`/`PersistenceState[]` branches, check the cap **before** polling, so
  a capped-out envelope (single state or whole array) is left untouched in the queue for the next
  `dequeue()` call. This preserves:
  - **Array atomicity** — the cap boundary is always between envelopes, never inside the
    `for (Object state : (PersistenceState[]) env.message())` loop, so a `persist(states...)` call's
    states can never be split across two transactions (the exact bug this atomicity guard was added
    to prevent — see the existing javadoc on `CreatureComponent.persist()`).
  - **FIFO ordering** — `ConcurrentLinkedQueue` is already FIFO; capping only changes where a single
    `dequeue()` call stops, not the order items leave the queue across successive calls.
  - **The `Flush` invariant** — unaffected. The existing "unrecognized message type" branch already
    breaks the loop whenever the head isn't a recognized batchable type, regardless of whether the
    cap or an empty queue caused the current batch to stop. A capped batch and a subsequent `Flush`
    can never land in the same `onReceive(List)` call, same as today. `bd-dispatcher` is a
    `PinnedDispatcher` that keeps looping while `hasMessages()` is true, so a large backlog now
    drains through several smaller transactions before `Flush` is ever reached, instead of one giant
    one — actually *reducing* the risk that used to make `CoordinatedShutdown`'s 30s `Flush` timeout
    a real hazard (see comment #3 on the issue: the drain itself timed out once during a crash).

**`src/main/java/br/cefetmg/lsi/l2l/common/ComponentMailbox.java`**
- Read an optional `max-batch-size` key from the `config` parameter already passed into its
  constructor (Akka instantiates one `MailboxType` per dispatcher referencing it, passing that
  dispatcher's own config subtree — confirmed via the existing `(ActorSystem.Settings, Config)`
  signature): `config.hasPath("max-batch-size") ? config.getInt("max-batch-size") : Integer.MAX_VALUE`,
  passed into `new ComponentMessageQueue(maxBatchSize)` in `create(...)`.

**Config** — add `bd-dispatcher { max-batch-size = 500 }` to all three places `bd-dispatcher` is
defined (confirmed all three currently lack this key and would otherwise diverge):
- `src/main/resources/application.conf`
- `config/docker-config.conf`
- `config/ccad-config.conf`

Leave `component-dispatcher` unset (unbounded, current behavior) — stimulus-batching there isn't
implicated in this OOM. 500 is a starting value, not a precisely-tuned final answer: the goal is
orders of magnitude below the observed ~965K-entity failure, not a specific optimum — validate/tune
against the `dl2l_bdactor_batch_size` gauge in the mini-experiment below.

### 3. Add the missing `dl2l_bdactor_queue_depth` gauge

**`src/main/java/br/cefetmg/lsi/l2l/creature/bd/BDActor.java`** — in `onReceive`'s `List` branch
(same spot `dl2l_bdactor_batch_size` is already set), sample the mailbox's current backlog via
Akka's public cell introspection:
```java
int queueDepth = ((akka.actor.ActorRefWithCell) getSelf()).underlying().numberOfMessages();
metricsExt.setGauge("dl2l_bdactor_queue_depth", queueDepth);
```
This is exactly what `docs/plans/bdactor-async-persistence-with-drain.md` §5 originally specified
but never implemented, and it's one of the issue's own "suggested next steps."

### 4. Diagnostic JVM flags (currently missing — confirmed no heap-dump/GC flags exist anywhere)

**`scripts/run-dl2l.sh`** — add `-XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=<dir>` and a GC
log (`-Xlog:gc*:file=<dir>/gc.log:time,uptime,level,tags:filecount=5,filesize=50M`), with `<dir>`
either a new positional arg or an env var, mirroring what the investigation itself used locally to
get the heap dump this whole plan is based on.

**`docker/docker-compose.yml`** — add a writable bind-mounted `heapdumps/` volume for
`dl2l-holder` (the role that crashes), matching the pattern already used for other mounts.

**`.gitignore`** — confirm/add coverage for the new mount path so a stray `.hprof`/`gc.log` is
never accidentally committed.

## Tests

**`src/test/java/br/cefetmg/lsi/l2l/common/ComponentMessageQueueTest.java`** (existing file) — add:
1. Cap limits the number of top-level envelopes merged per `dequeue()`; remainder stays queued for
   the next call.
2. Cap never splits a `PersistenceState[]` array across two dequeues — a capped-out array envelope
   is deferred whole, not partially consumed.
3. Existing/no-arg-constructor tests continue to pass unmodified, plus one explicit regression test
   enqueuing far more than any plausible cap with the default (unbounded) constructor, confirming
   `component-dispatcher`'s current behavior is untouched.
4. A large backlog followed by an unrecognized (Flush-like) message takes `ceil(N/cap)` separate
   capped `dequeue()` calls before the Flush-like object is ever returned, always alone.

**New `src/test/java/br/cefetmg/lsi/l2l/common/ComponentMailboxTest.java`** (currently no coverage):
- `create(...)` with a `Config` containing `max-batch-size` enforces that cap on the returned queue.
- `create(...)` with a `Config` lacking the key stays unbounded (mirrors `component-dispatcher` today).

No new `BDActor`-level test infra (no DB mocking exists in this repo today, consistent with how
PR #76 handled the same gap) — rely on the Docker Compose repro + mini-experiment for end-to-end
coverage of the gauge and Flush-timing behavior under load.

## Verification

1. `mvn package` compiles clean; `mvn test` passes, including the new tests above.
2. Reproduce the exact repro from the issue: `cd docker`, point `SIMULATION` at
   `simulations/20260717_memory_vs_wm_dense_no_reposition_1_baseline.conf` (noUI=false),
   `docker compose up -d`, watch `docker stats dl2l-holder-1` and the UI at `:8090` for several
   minutes. Confirm: no OOM, RSS stays well under `-Xmx2g`, `component-dispatcher` thread count
   stays near `parallelism-max=6` (spot-check via `jstack`), the new/existing `dl2l_bdactor_*`
   gauges and the `:9091/metrics` endpoint itself stay responsive throughout (endpoint
   unresponsiveness was itself a casualty of the original crash).
3. **Mini-experiment** (CLAUDE.md development-cycle step 5):
   - **Hypothesis:** caching `bd()` + capping BDActor's per-transaction batch size eliminates the
     sustained-load OOM without materially changing simulation behavior/data quality.
   - **Sample:** re-run the same condition the issue reproduced against, for 20-30 minutes (well
     past the ~8-minute crash time observed in the investigation's comment #3). 1-3 trials is
     sufficient — this is a binary stability/OOM check (crash vs. no crash), not a question needing
     a formal statistical sample-size calculation.
   - Spec at `experiments/p77_bdactor_oom_fix.yml`, run via
     `cd ansible && ansible-playbook -i inventories/local run-experiment.yml -e experiment=p77_bdactor_oom_fix`.
   - Analysis at `analysis/experiments/p77_bdactor_oom_fix.py`, plotting `dl2l_bdactor_queue_depth`/
     `batch_size`/`persist_duration_seconds` and thread counts over the run.
   - Report at `docs/reports/p77_bdactor_oom_fix.md` (Purpose/Assumptions/Hypothesis/Results/Analysis).
   - Explicitly check whether the residual `akka.remote.default-remote-dispatcher` backlog (MAT
     Suspect 2) shrinks as a side effect or needs its own follow-up issue (quick `jstack` spot-check
     during the run — no new permanent metric needed for this check).
   - Confirm `Flush`/`CoordinatedShutdown`'s 30s drain timeout still holds under sustained load with
     the new capped-batch behavior.
   - Data uploaded to `felipedreis/dl2l-experiments` under `p77/` per CLAUDE.md's upload policy.

## Deferred (separate follow-up issue, not part of this work)

File a new issue for the JPA→direct-JDBC migration once this fix's mini-experiment confirms whether
it alone resolves the OOM. If pursued, scope it to just `BDActor`'s write path +
`CreatureActor.java`'s birth/death persist (hand-rolled dependency-ordered inserts replacing
`cascade=ALL` for `StimulusState`/`ChangeStimulusState` + ~9 other cascade entities), explicitly
leaving `analysis/extractor/*`/`DataAnalyser.java`'s JPQL read path on JPA. Also flag the very old
`org.postgresql:postgresql:9.3-1102-jdbc4` driver version as a good-hygiene bump, independent of
whether the migration itself happens.
