# Postmortem: CCAD node c1 cognitive-cycle stall

**Status:** Resolved. **Duration:** 2026-07-27 to 2026-07-29 (~3 days across three PRs).
**Severity:** Confounded a completed experiment's headline finding; degraded creature
cognition throughput by up to ~40x on affected nodes before the fix, cluster-wide by an
unknown amount before that (the fix turned out to speed up every node, not just the
affected one).

## Summary

`20260717_memory_vs_wm_dense_scarce`'s headline result (scarcity inverts JEPA's survival
advantage) turned out to be confounded: creatures on CCAD nodes c1/c2 processed cognitive
cycles 6-40x slower than creatures on other nodes, for reasons entirely unrelated to the
experiment's actual manipulation (scarcity, JEPA vs. memory). Chasing this down took three
separate root causes, fixed in three sequential PRs, each of which improved things but none
of which alone fully explained the anomaly:

| PR | Fix | What it actually explained |
|----|-----|------------------------------|
| [#74](https://github.com/felipedreis/dl2l/pull/74) | Bound `component-dispatcher`/`default-dispatcher` Akka fork-join thread pools | A real oversubscription bug (18 threads on a 6-CPU cgroup), but fixing it alone made the observed symptom *worse* |
| [#75](https://github.com/felipedreis/dl2l/pull/75) | Share one JPA `EntityManagerFactory` per JVM instead of one per creature-component actor | Eliminated `SequencingManager` lock contention from ~100 independent factories, but didn't touch the still-synchronous persist path |
| [#76](https://github.com/felipedreis/dl2l/pull/76) | Route persistence through a revived `BDActor`, async/batched, off the cognition dispatcher, with a guaranteed shutdown drain | The actual dominant bottleneck: every `persist()` call blocked cognition on a real Postgres transaction |

After all three landed, a validation run forced onto the previously-worst node (c1) showed
mean cognitive-cycle rates of **13.8-28.6 cycles/s** — not just recovered, but **10-20x
faster than the "healthy" baseline** (~1.5-2.0 cycles/s) measured on node c11 earlier in the
investigation, because removing synchronous blocking I/O from the cognition path sped up
every node, not just the previously-bad one.

## Timeline

- **2026-07-27**: `20260717_memory_vs_wm_dense_scarce` runs on CCAD, 5 conditions × 5
  trials × 10 creatures. Result looks internally inconsistent — some trials show implausibly
  long survival times. See `docs/reports/20260717_memory_vs_wm_dense_scarce_report.md` for
  the full experiment write-up and its Methodology Note flagging this.
- **2026-07-28**: Root-cause investigation begins. A new Prometheus counter,
  `dl2l_creature_cognitive_cycles_total` (incremented once per
  `PartialAppraisal.onReceive()`, including the unconditional 1Hz keep-alive tick), plus
  standard JVM GC/CPU/thread metrics, are added specifically to diagnose this
  (`docs/plans/ccad-dispatcher-parallelism-fix.md`'s originating investigation). Confirms:
  node c1 shows creatures fully flat (zero cycle progress) for 5+ minute stretches; node c11
  stays healthy throughout.
- **2026-07-28/29**: First root cause found and fixed — unbounded Akka dispatcher thread
  pools (PR #74). Re-testing on c1 shows the symptom got *worse*, not better — the
  smoking gun that thread count was never the scarce resource.
- **2026-07-29**: Second root cause found via a live JVM thread dump (`kill -QUIT` on a
  running holder, via `srun --overlap` on the CCAD login node) — multiple
  `component-dispatcher` threads parked on the same EclipseLink `SequencingManager` lock
  object. Fixed by sharing one `EntityManagerFactory` per JVM (PR #75).
- **2026-07-29**: Independent review (`docs/plans/bdactor-async-persistence-with-drain.md`'s
  Context section) surfaces that neither fix touched the still-synchronous `persist()` call
  path. A dormant `BDActor` — built for exactly this, abandoned after a past incident where
  creature persistence stopped mid-write — is revived with a proper ask-based drain protocol.
  Three additional bugs are found and fixed live via Docker Compose smoke testing during
  this implementation (PR #76): a mailbox spin-hang on unrecognized message types, a
  discarded-sender bug that broke the drain's ask/reply, and a batching-atomicity bug that
  crashed the new actor outright on a duplicate-key constraint violation.
- **2026-07-29**: All three PRs merged. Validation run on node c1: cognitive-cycle rates
  10-20x above the original "healthy" baseline. Investigation closed.

## Root cause 1: unbounded Akka dispatcher thread pools (PR #74)

`component-dispatcher` (the mailbox pool running `PartialAppraisal`,
`HomeostaticRegulation`, and every other creature-cognition component) had no explicit
`fork-join-executor` configuration in `application.conf`/`config/docker-config.conf`/
`config/ccad-config.conf`, so it silently inherited `akka.actor.default-dispatcher`'s
built-in Akka default (`parallelism-factor = 3.0`, tuned for I/O-bound work). A live thread
dump plus the JVM's own `system_cpu_count` metric confirmed the mechanism precisely:
`6 cores × 3.0 = 18` — exactly matching 18 observed `component-dispatcher` threads on a
CCAD trial's 6-CPU cgroup slice.

Fix: explicit, CPU-bound-sized bounds (`parallelism-min=2, factor=1.0, max=6` for
`component-dispatcher`; `min=2, factor=1.0, max=8` for `default-dispatcher`), applied
identically across all three config copies.

**This fix alone made the observed stall worse**, not better — a strong, well-evidenced
signal (confirmed by re-testing on c1 immediately after) that thread *count* was never the
scarce resource; something was already occupying the pool with blocking work, and shrinking
the pool just serialized more actors behind it.

## Root cause 2: per-component-actor `EntityManagerFactory` duplication (PR #75)

`ComponentActor.preStart()` called `JpaPersister::new`, and `JpaPersister`'s no-arg
constructor independently called `Persistence.createEntityManagerFactory("L2LPU", ...)`.
Every component of every creature (~10 components × 10 creatures = ~100 actors per holder)
built its own factory — ~100 separate JDBC connection pools against the same Postgres
instance. A live thread dump, taken mid-stall on c1, caught multiple
`component-dispatcher` threads blocked on the *same* EclipseLink `SequencingManager` lock
object, one of them doing a synchronous blocking Postgres round-trip to fetch a new
entity-ID sequence value.

Fix: `PersistenceExtension`, an Akka Extension (the same per-JVM-singleton pattern already
used by `MetricsExtension`) holding one shared `EntityManagerFactory` per JVM.
`EntityManagerFactory` is thread-safe per the JPA spec; every caller still gets its own
`EntityManager`, just from a shared, warm factory instead of a freshly-built one.

This fix genuinely helped (peak single-creature stall duration dropped from ~540s to
~320s in the next test), but the core anomaly — mean cognitive-cycle rate still ~0.08-0.12/s
on c1 vs. c11's ~1.5-2.0/s — persisted.

## Root cause 3: synchronous persistence still blocking cognition (PR #76)

Neither prior fix touched the fact that `CreatureComponent.persist()` still did a fully
synchronous `begin()`/`persist()`/`commit()`/`clear()` JPA transaction **inline on
`component-dispatcher`**, for every one of ~14 call sites across creature components
(`PartialAppraisal`, `HomeostaticRegulation`, `Valuation`, sensors, etc.) — a real,
uncontested but very real Postgres round-trip on the same thread pool running creature
cognition, every single tick.

An unused `BDActor` already existed for exactly this purpose (batch persistence writes off
the cognition dispatcher), abandoned after — per the user — a past incident where the
simulation stopped before it finished writing queued data. Two latent bugs in the dead code
explained why: a fixed actor name (`"db"`) created per-creature, crashing on the second
creature; and `ComponentMessageQueue.dequeue()` (the mailbox behind both
`component-dispatcher` and `bd-dispatcher`) having no default branch for message types
other than `Stimulus`/`PersistenceState`/`String`/`PoisonPill` — capable of spinning a
dispatcher thread forever with no exception or log line.

`BDActor` was revived as one actor per JVM, owned by `PersistenceExtension`. Persistence
calls became fire-and-forget `creature.bd().tell(states)`. The user's past-incident concern
(guaranteeing no write is lost when the simulation stops) was addressed with an explicit
ask-based `Flush`/`FlushAck` protocol — mailbox FIFO ordering guarantees every write queued
before a `Flush` is committed before it's acked — invoked at both of `Holder`'s shutdown
paths, plus a `CoordinatedShutdown` task as defense-in-depth for paths that bypass those.

Three more real bugs surfaced during implementation, all confirmed live via Docker Compose
smoke testing rather than just reasoned about:

1. **Mailbox spin-hang** (the bug above) — fixed by adding the missing default branch.
2. **Discarded sender**: `ComponentMessageQueue.dequeue()` always hardcoded
   `Envelope.apply(list, ActorRef.noSender())`, silently discarding the original sender —
   so `BDActor`'s `sender().tell(new FlushAck(), self())` always replied to `deadLetters`,
   and every `Flush` ask timed out after 30s. Fixed by preserving the original envelope's
   sender for the single-unrecognized-message case.
3. **Batching atomicity**: `CreatureComponent.persist(states...)` initially sent one
   `.tell()` per state. Some states reference each other (e.g. an `EyeState`/
   `ObjectSeenState` `@OneToOne` pair) — if the two ends of that reference landed in
   *different* `BDActor` batches/transactions, the second transaction re-inserted the
   already-committed, now-detached entity and crashed `BDActor` outright on a
   `duplicate key value violates unique constraint "change_stimulus_state_pkey"` error
   (uncaught, so this project's `StoppingSupervisorStrategy` stopped rather than restarted
   the actor — cascading into every subsequent `Flush` failing with "recipient already
   terminated"). Fixed by sending the whole `states` array as one message, kept atomic by
   `ComponentMessageQueue`.

A candidate fourth fix — forcing Postgres-native sequences via
`eclipselink.sequencing.default-sequence-to-table=false` — was tried, then deliberately
reverted after isolating it: two clean back-to-back smoke test runs *without* that property
(atomicity fix alone) both completed with zero duplicate-key errors, confirming the
atomicity fix was sufficient by itself and the sequencing property had no real effect
(verified: it never actually created a native Postgres sequence).

## Verification

- Each PR was verified independently: `mvn test` (215 tests, up from 204 at the start of
  this investigation), a local Docker Compose smoke test, and — for PR #75/#76 — a live
  JVM thread dump confirming the specific mechanism was gone (no threads blocked in
  `SequencingManager`, no threads blocked in `JpaPersister`, exactly one `bd-dispatcher`
  thread doing all the writes).
- Final end-to-end validation: `20260728_tick_rate_diagnostic` (the throwaway diagnostic
  experiment created for this investigation, not part of the real scientific dataset) run
  on CCAD forced onto node c1 via `-e ccad_sim_exclude_nodes=c2,...,c11`, after all three
  fixes had landed. Result: mean cognitive-cycle rate 13.8-28.6 cycles/s across 5 trials,
  10-20x above the ~1.5-2.0 cycles/s baseline measured on the previously-healthy node c11
  earlier in the investigation. Apparent remaining "stalls" (long flat stretches in the
  per-creature metric) were confirmed to be natural creature deaths in this scarcity
  simulation (lifetimes of 65-478s, well within the run window), not live cognition
  stalls — the metric simply reports its last value forever once a creature's actor stops.

## Methodology notes / what worked

- **Live JVM inspection on a shared HPC cluster**: `srun --jobid=<N> --overlap <cmd>` lets
  you run arbitrary commands inside an already-running SLURM job's allocation from the
  login node — used repeatedly to inspect cgroup state, list processes, and (via
  `kill -QUIT`) trigger thread dumps on a live, unmodified holder JVM mid-stall. This was
  the single most useful technique in the whole investigation; every root cause was found
  by looking at a live thread dump, not by reasoning about the code in the abstract.
- **Don't trust a hypothesis until the intervention is tested.** Each of the three fixes
  was individually plausible and evidence-backed at the time it was made, but only the
  third one turned out to be the dominant effect — and the first fix's "made it worse"
  result was the clue that kept the investigation going instead of stopping short.
- **An independent, fresh-context review caught a real gap.** After PR #75 didn't fully
  resolve the issue, a fresh subagent review (no shared context with the investigating
  session) surfaced the still-synchronous persist path and correctly predicted it as the
  likely dominant remaining cause — subsequently confirmed.
- **Docker Compose smoke tests, not just unit tests, caught two of the three implementation
  bugs in PR #76** (the discarded-sender bug and the batching-atomicity bug) — neither would
  have been caught by `mvn test` alone, since this repo has no test-DB infrastructure to
  exercise real JPA/Postgres behavior at the unit level.

## Related documents

- `docs/reports/20260717_memory_vs_wm_dense_scarce_report.md` — the experiment report whose
  headline finding motivated this investigation.
- `docs/plans/ccad-dispatcher-parallelism-fix.md` — PR #74's design and investigation notes.
- `docs/plans/shared-entity-manager-factory.md` — PR #75's design and investigation notes.
- `docs/plans/bdactor-async-persistence-with-drain.md` — PR #76's design, investigation
  notes, and the detailed verification log for all three bugs found during implementation.
