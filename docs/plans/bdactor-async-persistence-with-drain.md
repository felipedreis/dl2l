# Route creature persistence through BDActor (async, batched) with a guaranteed drain on shutdown

## Context

PR #74 bounded `component-dispatcher`/`default-dispatcher` fork-join pools. PR #75 added
`PersistenceExtension` (one shared JPA `EntityManagerFactory` per JVM), fixing the
`SequencingManager` lock-contention stall confirmed live on CCAD node c1. Neither PR touched
the remaining, still-live problem: `CreatureComponent.persist()` calls
`JpaPersister.persist()` **synchronously, on `component-dispatcher`**, doing a real
begin/persist/commit/clear against Postgres inline with cognition (`PartialAppraisal`,
`HomeostaticRegulation`, etc. all call `persist()` directly — see call sites in Eye, Nose,
Mouth, Body, SensoryCortex, EffectorCortex, PartialAppraisal, FullAppraisal,
HomeostaticRegulation, Valuation, NeuromodulatorSystem, EndocrineSystem).

`BDActor` (`br.cefetmg.lsi.l2l.creature.bd.BDActor`) already exists for exactly this: batch
many `PersistenceState`s into one transaction, off `component-dispatcher`, on its own
`bd-dispatcher` (`PinnedDispatcher` + `ComponentMailbox`, already declared in
`application.conf`/`docker-config.conf`/`ccad-config.conf`). It is dead code — nothing sends
it messages, and its one call site (`CreatureActor.init()`) is commented out.

### Key discovery: the scaffolding for this exact feature already exists, unused

- `Creature.bd()` is already a method on the `Creature` interface.
- `CreatureActor.bd()` is already implemented — but returns `deadLetters()` today because the
  `bdActor` field is never assigned (its only assignment,
  `context.system().actorOf(Props.create(BDActor.class, em)..., "db")`, is commented out in
  `CreatureActor.init()`).
- `TestingCreature` **already** wires a `bd` `ExternalSink` for `Creature.bd()` and exposes it
  via `TestingHarness.bdSink()` — present, wired, but currently asserted on by zero tests
  (nothing currently verifies *what* gets persisted, since `NoOpPersister` silently swallows
  everything).
- `ComponentMessageQueue` (the mailbox behind `ComponentMailbox`, used by both
  `component-dispatcher` and `bd-dispatcher`) already special-cases `PersistenceState`
  instances: its `dequeue()` drains the *entire* queue on every poll, coalescing every
  `Stimulus` **or** `PersistenceState` sitting in front of a `PoisonPill` into one `List`
  delivered as a single `onReceive` call. This means the intended calling convention was
  always "`.tell()` individual `PersistenceState`s to BDActor, let the mailbox auto-batch
  them," not "build a `List` yourself and `.tell()` that" — which matches `BDActor.onReceive`
  expecting `instanceof List`.

This means reviving BDActor is much less "build new infrastructure" and much more "finish
wiring what's already there, plus fix why it was abandoned and add the drain guarantee that
was never built."

### Why the original wiring was abandoned (and why the user's past incident happened)

Two independent latent bugs are visible in the current dead code, either of which is fatal on
its own:

1. **Duplicate actor name crash.** The commented-out line creates BDActor with
   `context.system().actorOf(..., "db")` **inside `CreatureActor.init()`** — called once per
   creature. The second creature on any holder would crash with
   `InvalidActorNameException: actor name [db] is not unique`. This alone explains why it was
   never turned on for anything beyond a single-creature smoke test.
2. **`ComponentMessageQueue.dequeue()` has no default branch.** Its `while` loop only handles
   `Stimulus`/`PersistenceState` (batch), `String` (discard), `PoisonPill` (break early). Any
   *other* message type sitting at the head of the queue matches none of these branches, so
   the loop neither advances (`poll()`) nor exits — it spins forever, hanging that mailbox's
   dispatcher thread. `BDActor.onReceive` expects a bare `List`; if anything ever `.tell()`ed
   a pre-built `List` (rather than individual `PersistenceState`s) to a `ComponentMailbox`-based
   actor, or if a `Flush`-style control message were sent to it, it would silently deadlock the
   `bd-dispatcher` thread with no exception, no log line — exactly the kind of failure that
   looks like "the actor didn't finish" from the outside.
3. **`BDActor.postStop()` only commits an *already-active* transaction.** It does nothing for
   messages still sitting *unprocessed in the mailbox* when the actor is stopped. Akka
   distinguishes ordinary messages (queued, FIFO, processed via the mailbox) from actor
   lifecycle control (`context().stop(ref)`, which sends an internal system-level `Terminate`
   that bypasses the mailbox and is processed ahead of any queued user messages). **Anywhere
   in this codebase that calls `context().stop()` on an actor with a persistence actor as a
   descendant will drop that descendant's queued-but-unprocessed messages to `deadLetters`,
   silently.** `Holder.handleRemoveObject()` calls exactly this —
   `context().stop(componentActor)` on the whole `CreatureActor` subtree the moment a creature
   dies — and `CreatureActor.kill()` also calls `context().stop()` on each component
   individually. If `BDActor` were (as originally coded) a *child of each `CreatureActor`*,
   any of its just-`tell()`ed-but-not-yet-committed writes at the moment of `kill()`/removal
   would be discarded outright. This is almost certainly the exact mechanism behind the user's
   past incident ("actor didn't finish writing all of this stuff yet" when the simulation
   stopped) — not a fundamental flaw in async writes, but a stop-vs-mailbox-ordering bug in how
   the actor was integrated.

None of this means "don't use an actor." Per CLAUDE.md's own "Akka Actor Anti-Patterns"
section (already followed by `MetricsExtension` and `PersistenceExtension`), the standard,
idiomatic pattern for a per-JVM singleton service is *exactly* "Extension holds/creates an
`ActorRef` to a dedicated service actor" — an Extension-only design without a backing actor
would either (a) require `EntityManager` (not thread-safe) to be called concurrently from many
`component-dispatcher` threads, reintroducing the original PR #75-class contention problem, or
(b) require the Extension to internally serialize access with a lock, which is just an actor's
mailbox reimplemented worse. The real fix is: keep the actor, fix (1) how it's addressed, (2)
its mailbox's robustness, and (3) how shutdown drains it — using Akka's actual idiomatic
tools for that (`CoordinatedShutdown`, explicit ask-based `Flush`, never bare `context().stop()`
on it or an ancestor of it).

## Design

### 1. One `BDActor` per JVM (holder), owned by `PersistenceExtension`

Extend `PersistenceExtension.Impl` (not a new Extension — this codebase's existing precedent,
`MetricsExtension`/`MLServiceExtension`, is "one Extension per per-JVM *resource category*,
bundling the resource with the service `ActorRef` that guards it," and `PersistenceExtension`
is already the canonical owner of the shared `EntityManagerFactory` BDActor needs):

```java
// PersistenceExtension.Impl
Impl(ExtendedActorSystem system) {
    this.emf = Persistence.createEntityManagerFactory("L2LPU", JpaPersister.jdbcUrlOverride());
    this.bdActor = system.actorOf(
            Props.create(BDActor.class).withDispatcher("bd-dispatcher"), "bd");
    CoordinatedShutdown.get(system).addTask(
            CoordinatedShutdown.PhaseBeforeActorSystemTerminate(), "drain-bdactor",
            () -> drain(system));  // see Design §2
}

public ActorRef bdActor() { return bdActor; }
```

Because `AbstractExtensionId`/`registerExtension` guarantees the Extension's constructor runs
exactly once per `ActorSystem`, `system.actorOf(..., "bd")` also runs exactly once — this by
itself fixes bug (1) above (no more "second creature crashes with duplicate name").

`BDActor` builds its own `EntityManager` from the same shared factory, inside its own
constructor (so it's confined to the single `bd-dispatcher` `PinnedDispatcher` thread —
`EntityManager` is not thread-safe, but here it's only ever touched by one dedicated OS
thread, same safety argument as `ComponentActor`'s current per-component `EntityManager`
today, just now singular):

```java
public BDActor() {
    this.em = PersistenceExtension.of(getContext().system())
            .entityManagerFactory().createEntityManager();
}
```

### 2. Addressing: `Creature.bd()` resolves the extension, never a raw actor reference

`CreatureActor.bd()`:

```java
@Override
public ComponentRef bd() {
    return new AkkaComponentRef(
            PersistenceExtension.of(TypedActor.context().system()).bdActor());
}
```

Delete the dead `bdActor` field and its commented-out assignment in `CreatureActor.init()`.
`CreatureComponent`s never hold an `ActorRef`/`Persister`/`EntityManager` directly — they only
ever hold `creature` (a `Creature`/`ComponentRef` interface reference, already how
`holder()`/`memoryConsolidator()` work today), satisfying CLAUDE.md's "never share object
instances directly between actors" rule the same way every other cross-actor call in this
codebase already does.

`CreatureComponent.persist()` changes from calling the injected `Persister` to telling
`creature.bd()` once per state (so `ComponentMessageQueue`'s existing batching does the
coalescing, matching the mailbox's actual design):

```java
protected final void persist(PersistenceState... states) {
    if (creature == null) return;
    logger.fine(() -> "persisting " + states.length + " state(s)");
    for (PersistenceState state : states) {
        creature.bd().tell(state);
    }
}
```

No call site in `Eye`/`Nose`/`Mouth`/`Body`/`SensoryCortex`/`EffectorCortex`/
`PartialAppraisal`/`FullAppraisal`/`HomeostaticRegulation`/`Valuation`/`NeuromodulatorSystem`/
`EndocrineSystem` needs to change — they all call the base-class `persist(...)` varargs method,
which now fires-and-forgets instead of blocking.

**`ComponentActor` / `Persister` / `JpaPersister` / `NoOpPersister`:** leave the `init()`
signature and `Persister` parameter untouched (matches this repo's own stated precedent in PR
#75 — "not removed to avoid narrowing `Persister`'s public API beyond what's needed"). Since
`persist()` no longer calls the injected `Persister` in production, `ComponentActor.preStart()`
no longer needs to build a real `JpaPersister`+`EntityManager` per component at all — pass a
shared `NoOpPersister` instance instead. This is a bonus resource reduction beyond this plan's
core ask (removes the *remaining* one-`EntityManager`-per-component-actor allocation that PR
#75 didn't touch, since it only shared the *factory*, not eliminated the per-component
`EntityManager`). `Persister`/`JpaPersister`/`NoOpPersister` classes stay as-is, now fully
unused in the component path but harmless — an optional follow-up cleanup PR, not required
here.

### 3. Fix `ComponentMessageQueue`'s missing default branch (prerequisite bug fix)

Add an explicit fallback so any message type other than `Stimulus`/`PersistenceState`/
`String`/`PoisonPill` is treated as a single-message batch (mirrors the existing `PoisonPill`
early-break, just without stopping the actor):

```java
} else {
    if (list.isEmpty()) {
        list.add(env.message());
        queue.poll();
    }
    break;
}
```

This is required before introducing the `Flush` control message (§4) — without it, `Flush`
would hang the `bd-dispatcher` thread exactly as described in Context bug (2). It's also a
correctness fix for `component-dispatcher` generally (any future non-`Stimulus` message type
sent to any component would hit the same latent hang today). Add
`ComponentMessageQueueTest` covering: `Stimulus` batching, `PersistenceState` batching,
`String` discard, `PoisonPill` barrier (existing behaviour, now regression-protected), and the
new unrecognized-type passthrough.

### 4. Drain protocol: explicit ask-based `Flush`, never bare `context().stop()`

New messages in `br.cefetmg.lsi.l2l.creature.bd`:

```java
public record Flush() implements Serializable {}
public record FlushAck() implements Serializable {}
```

`BDActor.onReceive`:

```java
if (message instanceof List) {
    // existing batch-persist logic, unchanged
} else if (message instanceof Flush) {
    // Mailbox FIFO ordering (fixed in §3) guarantees every List/PersistenceState
    // enqueued strictly-before this Flush was already delivered and committed in
    // an earlier onReceive call, so there is nothing left to do here except ack.
    sender().tell(new FlushAck(), self());
} else if (message instanceof PoisonPill) {
    ...
}
```

Callers drain with the existing `Sync.ask` helper (`br.cefetmg.lsi.l2l.cluster.Sync`, already
used by `SimulationManager`/`Holder` for other bounded synchronous waits) — no new pattern
introduced, reuses this codebase's own idiom:

```java
Sync.ask(PersistenceExtension.of(context().system()).bdActor(), new Flush(), 30);
```

Two drain points, both required (this is what directly answers the user's concern —
"guaranteed, not hoped for"):

**a. `Holder.handleRemoveObject()`**, the moment `creatures.isEmpty()` — drain BEFORE
constructing `DataAnalyser`:

```java
if (creatures.isEmpty()) {
    Sync.ask(PersistenceExtension.of(context().system()).bdActor(), new Flush(), 30);
    EntityManager em = PersistenceExtension.of(context().system())
            .entityManagerFactory().createEntityManager();
    DataAnalyser analyser = new DataAnalyser(em, saveDir);
    ...
}
```

This is not just a shutdown nicety — `DataAnalyser` reads creature state back out of Postgres
by ID (`CreatureState.findAllCreatureIds` + per-creature routines) to produce the final
analysis. Without this drain, moving persistence off the synchronous path means the analysis
could silently read a DB missing each creature's final ticks — a correctness regression this
plan must not introduce.

**b. `Holder.handleFinish()`**, defensive second drain before `context().stop(self())` /
`context().system().terminate()` — cheap, idempotent (nothing left to flush in the common case
since (a) already ran), and covers any holder that never held a creature, or any future
persistence path added later that doesn't go through the per-creature-death drain.

**c. `CoordinatedShutdown` task** (registered in `PersistenceExtension.Impl`'s constructor,
§1, in `PhaseBeforeActorSystemTerminate` — the phase explicitly documented in Akka's own
`reference.conf` for "custom application tasks... run after cluster shutdown and before
ActorSystem termination"). This is defense-in-depth for shutdown paths that bypass
`Holder.handleFinish()` entirely: a crashed/killed holder JVM, `docker-compose down`/SIGTERM,
or a cluster `Down` event. Verified (via Akka 2.5.32's own `reference.conf`, this project's
bundled version) that `CoordinatedShutdown` runs automatically both from
`ActorSystem.terminate()` and from the JVM shutdown hook
(`run-by-jvm-shutdown-hook = on` is the default, unchanged in this project's config), so this
task fires on every realistic shutdown path, not only the "happy path" one already covered by
(a)/(b).

None of these three drains explicitly stops `BDActor` — it's left to Akka's normal
`actor-system-terminate` teardown, which only runs *after* the `CoordinatedShutdown` task in
(c) has completed, so by construction `BDActor`'s mailbox is already empty and any in-flight
transaction already committed before the actor tree is torn down. `BDActor.postStop()` keeps
its existing "commit if a transaction is still active" line purely as a last-resort safety net
(should be unreachable in the normal path after this change), plus closes its `EntityManager`.

### 5. Backpressure

`bd-dispatcher` stays a single `PinnedDispatcher` thread — a deliberate serialization point
(only one JDBC/EclipseLink session, avoiding PR #75's original contention class of bug
entirely by construction, not by luck). The existing `ComponentMessageQueue.dequeue()`
behavior (drain everything queued into one transaction per poll) is a **self-adaptive
micro-batching backpressure mechanism "for free"**: the busier BDActor is, the larger (and
thus more Postgres-round-trip-efficient) each batch becomes, rather than falling further and
further behind — this is the standard database-writer backpressure pattern used across the
industry (batch-on-backlog), not something to build from scratch.

Recommendation: **do not add a bounded mailbox / hard backpressure now.** A bounded mailbox
that blocks or drops when full would reintroduce blocking (or worse, silent data loss) on the
`component-dispatcher` thread — exactly what this plan removes. Fire-and-forget must stay
fire-and-forget. Instead, add observability (this codebase already has `MetricsExtension` +
Prometheus wired for exactly this purpose, precedent: the tick-rate diagnostic gauges added
ahead of PR #73/#74/#75):

- `dl2l_bdactor_queue_depth` — `ComponentMessageQueue.numberOfMessages()`, sampled once per
  `onReceive(List)` call.
- `dl2l_bdactor_batch_size` — size of each committed batch.
- `dl2l_bdactor_persist_duration_seconds` — wall-clock time of each `begin`→`commit`.

If a future experiment's metrics show sustained, unbounded queue growth (writer genuinely
can't keep up, not just a transient burst), that is the trigger to revisit — e.g. a
size-based warning threshold or a policy for dropping low-value log-style states (e.g.
`NeuromodulatorStateLog`/`EndocrineStateLog`, which are logged every tick, vs. one-off state
transitions) under sustained backlog. Explicitly out of scope / deferred for this plan — no
evidence yet that it's needed, and simulations are already runtime-capped
(`MaxRuntimeExpired` in `SimulationManager`), bounding worst-case total backlog.

### 6. Test impact

**`TestingCreature`/`TestingHarness`: no required changes.** `TestingCreature.init()` already
wires `Creature.bd()` to the `bd` `ExternalSink`, and `CreatureComponent.persist()`'s only
behavioral change is `persister.persist(states)` → a loop of `creature.bd().tell(state)` — both
already fully supported today with zero modification, because this exact scaffolding
(`Creature.bd()`, `TestingCreature`'s `bd` sink, `TestingHarness.bdSink()`) was already built
and left unused. Confirmed via grep: no existing test currently asserts on `bdSink()` content
(it was wired but never exercised, since `NoOpPersister` silently swallowed everything before
this change) — so nothing breaks, and this refactor is a strict testability improvement:
authors can now assert `harness.bdSink().ofType(EyeState.class)` etc. after driving a
component, which was previously impossible to verify at all.

New unit tests to add:
- `ComponentMessageQueueTest` (§3) — pure, no `ActorSystem` needed (matches this repo's
  existing style, e.g. `SimulationSettingsExtensionTest`, which notes "no ActorSystem needed").
- `BDActorTest` — first `ActorSystem`-backed test in this repo. No `akka-testkit` dependency
  exists yet (checked `pom.xml`); avoid adding one — a plain `ActorSystem.create("test")` +
  this repo's own `Sync.ask` (or `Patterns.ask` + `Await.result`, same pattern) is sufficient
  to (a) send several `PersistenceState`s and assert they land in the DB in one transaction
  (use an in-memory/H2 EclipseLink persistence unit, or the existing test Postgres if this repo
  already spins one up for tests — check for an existing test persistence.xml profile before
  adding one), (b) send `Flush` and assert the ack only arrives after prior states are
  committed, (c) assert the unrecognized-message passthrough from §3 doesn't hang.
- A couple of `TestingCreature`-level tests newly asserting `bdSink()` contents for at least
  one component per persist-call-site category (state-log-style vs. one-off transition) as a
  regression net for the `persist()` rewire itself.

### 7. Scope boundary — explicitly NOT touched by this plan

- `MemoryConsolidator`/`MemoryTraceConsolidator`'s own direct `em.persist(...)` calls (own
  dedicated `wm-dispatcher` `PinnedDispatcher`, already isolated from `component-dispatcher`
  cognition — different actor, different bottleneck class, out of scope).
- `CreatureActor.kill()`'s synchronous born/dead-time `em.persist(state)` (runs on
  `default-dispatcher`, exactly twice per creature's lifetime — negligible frequency compared
  to the per-tick component call sites this plan targets). Noted as a low-priority future
  candidate to also route through `creature.bd()`, not required now.
- `Holder`'s `DataAnalyser` read path (unchanged; only its *ordering* relative to the new drain
  changes, per §4a).

## Implementation steps (suggested order)

1. `ComponentMessageQueue` default-branch fix + `ComponentMessageQueueTest` (§3) — standalone,
   safe, mergeable independently, de-risks everything downstream.
2. `PersistenceExtension.Impl`: add `bdActor` field/creation/`bdActor()` accessor +
   `CoordinatedShutdown` task registration (§1, §4c).
3. `BDActor`: switch to no-arg constructor pulling its `EntityManager` from
   `PersistenceExtension`; add `Flush`/`FlushAck` handling (§4); add the three metrics (§5).
4. `CreatureActor.bd()`: resolve through `PersistenceExtension.of(...).bdActor()`; delete the
   dead `bdActor` field + commented-out `actorOf(..., "db")` line.
5. `CreatureComponent.persist()`: rewire to `creature.bd().tell(state)` loop (§2).
6. `ComponentActor.preStart()`: stop constructing a per-component `JpaPersister`/
   `EntityManager`; pass a shared `NoOpPersister` instead (§2, bonus cleanup).
7. `Holder.handleRemoveObject()` + `Holder.handleFinish()`: add the two `Sync.ask(..., Flush)`
   drain calls (§4a, §4b).
8. New tests: `BDActorTest`, `bdSink()`-asserting `TestingCreature`/`TestingHarness` tests
   (§6).

## Verification

1. `mvn package` compiles clean; `mvn test` passes — 215 total (204 existing + `ComponentMessageQueueTest`'s
   9 cases + `BdSinkFunctionalTest`'s 2). No dedicated `BDActorTest` (§6's original plan) was
   added — this repo has no test-DB or mocking infrastructure (checked: no Mockito, no H2/test
   `persistence.xml` profile, no docker-postgres running for tests), and standing that up would
   have been a larger scope expansion than this task warranted. Real end-to-end coverage comes
   from the Docker Compose smoke tests below instead (real Akka, real Postgres, real BDActor).

2. **A second, real bug found and fixed during implementation, not anticipated by the original
   plan**: `Sync.ask(bdActor, new Flush(), 30)` initially timed out every time. Root cause:
   `ComponentMessageQueue.dequeue()` always returned `Envelope.apply(list, ActorRef.noSender())`
   — hard-coding the sender, discarding the original one. `BDActor`'s `sender().tell(new
   FlushAck(), self())` therefore always replied to `deadLetters`, not the actual asker. Fixed
   by tracking the original envelope's sender for the single-unrecognized-message case (the only
   case that needs it - a batch of several `Stimulus`/`PersistenceState` never had per-message
   sender tracking and still doesn't, since nothing reads it) and using that instead of the
   hardcoded default. Covered by `ComponentMessageQueueTest.unrecognizedMessageDeliveryPreservesItsOriginalSender`
   (using `system.deadLetters()` as the "original sender," specifically because it's a concrete
   `ActorRef` distinct from `ActorRef.noSender()` - asserting against `noSender()` itself
   wouldn't have caught this regression).

3. **A third, more serious bug found via the Docker Compose smoke test**: after fixing the
   sender bug, `BDActor` crashed outright on `ERROR: duplicate key value violates unique
   constraint "change_stimulus_state_pkey"` (an uncaught `RollbackException`, which this
   project's `StoppingSupervisorStrategy` stops rather than restarts the actor for) - which
   then made every subsequent `Flush` ask fail with "recipient already terminated." Root cause:
   `CreatureComponent.persist(PersistenceState... states)` originally sent one `.tell()` per
   state (§2's original design). Some states reference each other (e.g. an
   `EyeState`/`ObjectSeenState`'s `@OneToOne changeStimulusState`) - if the two ends of that
   reference landed in *different* `BDActor` batches/transactions (possible since they're sent
   as separate messages and `BDActor`'s single dispatcher thread could poll between them), the
   second transaction re-inserted the already-committed, now-detached (`em.clear()`'d after the
   first commit) referenced entity and hit the primary-key collision. **Fixed by sending the
   whole `states` array as one message** (`creature.bd().tell(states)`) instead of one `.tell()`
   per element, and extending `ComponentMessageQueue.dequeue()` to flatten a `PersistenceState[]`
   into the batch atomically (added in the same dequeue() iteration, never split across two).
   This restores the same one-`persist()`-call-one-transaction invariant the old synchronous
   design always had. Covered by `ComponentMessageQueueTest.persistStateArraysAreFlattenedIntoTheSameBatchAtomically`.
   (`ExternalSink`, `TestingCreature`'s test double for `bd()`, was also updated to flatten
   `PersistenceState[]` the same way, so `bdSink().ofType(...)` assertions keep working.)

   Before concluding this was purely an atomicity bug, also tried (and then reverted, since it
   turned out unnecessary) forcing Postgres-native sequences via
   `eclipselink.sequencing.default-sequence-to-table=false` in `persistence.xml` - this schema
   uses EclipseLink's default TABLE-based sequencing (confirmed via `psql`: a single shared
   `public.sequence` row, `SEQ_GEN`, used across every `@GeneratedValue(AUTO)` entity type; zero
   native Postgres sequences exist). The property had no observable effect (`pg_sequences` count
   stayed 0 after adding it) and, more importantly, two clean back-to-back smoke test runs
   *without* it (after the atomicity fix alone) both completed with zero duplicate-key errors
   and tens of thousands of successful writes - confirming the atomicity fix was sufficient by
   itself, and avoiding shipping an ineffective, misleading config change alongside it.

4. Docker Compose smoke test (`cd docker && docker-compose up`, default short-lived
   `baseline_1node_1creature.conf`, full creature-lifecycle-to-shutdown): after the two fixes
   above, two consecutive clean runs, zero `[ERROR]` log lines, zero duplicate-key errors,
   `data.change_stimulus_state` row counts in the tens of thousands (35,472 and 49,628 across
   the two runs) with no data-integrity issues. `AllCreaturesDead` → `Holder.handleFinish()`'s
   drain → shutdown sequence completed cleanly both times.

5. **A fourth, minor issue found and fixed**: the `CoordinatedShutdown` "drain-bdactor" task
   (§4c, defense-in-depth) logged a confusing `WARN` — `Recipient [...bd...] had already been
   terminated` — on every run, even though no data was lost. Root cause: `Holder.handleFinish()`
   calls `context().system().terminate()` directly (not `CoordinatedShutdown.run()`), which
   tears down `/user`'s children - including `bdActor` - without reliably waiting for the
   coordinated phases first; by the time the task ran, `Holder`'s own two explicit drains
   (§4a/§4b) had already flushed everything and `bdActor` was already gone - an expected outcome
   for this task in the common (already-covered-by-Holder) path, not a real failure. Fixed by
   treating "already terminated" as an already-successful drain in `PersistenceExtension.drain()`
   (matching on the exception message, since Akka's `ask` pattern uses the same
   `AskTimeoutException` type for both a genuine 30s timeout and an immediate
   already-terminated failure), while still surfacing any other exception as a real failure.
   Confirmed fixed: the third smoke test run (after this fix) had zero such warnings.

6. A live thread-dump check (`kill -QUIT`/`jstack` on the holder PID, same technique PR #75's
   investigation used) during a longer-running (18-minute diagnostic conf) Docker Compose run
   confirmed: zero `component-dispatcher` threads blocked inside `JpaPersister`/
   `SequencingManager` (grepped for those frames under every `l2l-component-dispatcher-*`
   thread — none found), and exactly one `l2l-bd-dispatcher` thread, actively persisting.

7. Not yet done: re-running `20260728_tick_rate_diagnostic` on CCAD forced onto node c1 to check
   whether the underlying stall (this whole investigation chain's original motivation) is
   actually resolved there. That's the real test of whether this fix (plus PR #74 and #75)
   fully explains the CCAD-specific anomaly, or whether something else remains - to be run once
   this PR is reviewed/merged, per the same pattern as PR #74/#75's CCAD validation.

## Housekeeping

Feature branch + PR (Java source change across `creature/bd/`, `creature/components/`,
`creature/`, `cluster/`, `common/`), per established preference — not direct to main.
