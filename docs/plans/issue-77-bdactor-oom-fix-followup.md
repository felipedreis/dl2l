# BDActor OOM fix follow-up: envelope cap alone was insufficient (issue #77 / PR #78)

## Context

PR #78 fixed issue #77's BDActor OOM by caching `bd()` and capping
`ComponentMessageQueue`'s per-transaction batch to `max-batch-size = 500` top-level
envelopes. Re-verifying the merged fix against the exact original repro
(`20260717_memory_vs_wm_dense_no_reposition_1_baseline.conf`, 10 creatures, UI on)
before merging surfaced a second, independent failure: the holder JVM still hit a
genuine `java.lang.OutOfMemoryError: Java heap space` on an `l2l-bd-dispatcher` thread,
crashing via `akka.jvm-exit-on-fatal-error`, despite `max-batch-size = 500` correctly
resolved and applied.

## Evidence

A `-XX:+HeapDumpOnOutOfMemoryError` dump from the crash (Eclipse MAT, headless
`suspects`/`top_components` reports, same tooling as the original issue #77
investigation) showed the dominant retained-heap consumer was, once again, a single
`RepeatableWriteUnitOfWork` on an `l2l-bd-dispatcher` thread - this time holding
**1,572,864 `CacheKey` entries**, with **`ChangeStimulusState`: 2,187,320 instances**
and **`PersistenceState[]`: 877,478 instances** in the class histogram. The
`dl2l_bdactor_batch_size` gauge (Prometheus, scraped every 5s throughout the run)
confirmed a real transaction with `batch.size() == 2,195,162` - three to four orders of
magnitude over the intended ~500-750-state cap.

## Investigation: root cause not conclusively identified

Extensive targeted reproduction attempts, all using the exact deployed JAR (verified via
`javap`/string search that the running container's JAR genuinely contained the fix, not
a stale build), failed to reproduce the envelope-cap bypass:

1. **Config wiring**: confirmed correct via a standalone probe - a real `ActorSystem`
   with `bd-dispatcher`/`component-dispatcher` both referencing `ComponentMailbox`,
   racing 2000 concurrent `component-dispatcher` actor creations against the single
   `bd-dispatcher` actor's creation, 5 iterations. `bd-dispatcher`'s mailbox resolved
   `max-batch-size = 500` correctly in all 5/5 runs; `component-dispatcher`'s correctly
   stayed unbounded. No cache collision between dispatcher IDs (Akka's
   `mailboxTypeConfigurators` cache is keyed by dispatcher ID, not mailbox class name).
2. **Code re-read**: `ComponentMessageQueue.dequeue()`'s cap check
   (`envelopesMerged >= maxEnvelopesPerBatch`) is structurally correct - a local
   variable, reset every call, checked before every `queue.poll()`.
3. **Call-site audit**: every `persist(...)` call site in `creature/components/*.java`
   passes 1-3 fixed `PersistenceState` args - no call site builds a large dynamic array,
   ruling out a single oversized envelope as the mechanism (the heap dump's
   `PersistenceState[]` count of 877,478 - each ~2.5 states average, matching the
   `ChangeStimulusState` count - confirms this really was ~877K *separate* envelopes
   merged into one batch, not one giant envelope).
4. **`PersistenceExtension` singleton**: `ActorSystem.registerExtension` guarantees
   exactly-once creation; only one `BDActor`/mailbox exists per JVM.
5. **`AkkaComponentRef.tell()`**: routes straight to `ActorRef.tell()`, no bypass of the
   mailbox.
6. **Live diagnostic re-run**: added temporary logging to `dequeue()` (envelope count,
   list size, queue identity, thread) and re-ran the exact repro end-to-end, including
   the mass-extinction burst (all 10 creatures dying together, driven by the
   cycle-coupled metabolic clock - see issue #77's "Related" section) and the
   `CoordinatedShutdown` drain. The cap held for **1987 consecutive calls**, exactly
   500 envelopes each, draining a 2.46M backlog to zero without incident - including with
   Prometheus/Grafana *also* scraping the same holder every 5s throughout (ruling out
   resource contention from the observability stack as a trigger; scrape CPU usage was
   0.24%, and it was successfully polling `dl2l-holder` at every attempt, not idle).

The failure is real (confirmed once via heap dump + Prometheus history, from a run using
the same image, same config, same simulation) but has not been reproduced under
instrumentation, making it very likely a rare race rather than a deterministic logic or
wiring bug - most plausibly a narrow interleaving around EclipseLink's cascade
registration (`UnitOfWorkImpl.registerNewObjectForPersist` →
`CollectionMapping.cascadeRegisterNewIfRequired` → `ObjectBuilder.cascadeRegisterNewForCreate`
appeared in the crash's heap-dump stack trace) rather than in `ComponentMessageQueue`
itself, though this was not conclusively isolated either.

## Fix: independent, redundant cap on total states

Rather than continue chasing an intermittent trigger, added a **second, independent**
cap - `max-states-per-batch` - alongside the existing `max-batch-size` (envelope count):

- **`ComponentMessageQueue`**: new `maxStatesPerBatch` field, own counter/comparison
  (`list.size() >= maxStatesPerBatch`), checked before consuming both a lone
  `Stimulus`/`PersistenceState` and a whole `PersistenceState[]` array - same
  before-polling / atomicity-preserving pattern as the envelope cap, so an in-flight
  array is still never split. A 3-arg constructor `(maxEnvelopesPerBatch,
  maxStatesPerBatch)` was added; both 0- and 1-arg constructors still default the new
  cap to `Integer.MAX_VALUE`, preserving `component-dispatcher`'s unbounded behavior.
- **`ComponentMailbox`**: reads an optional `max-states-per-batch` config key the same
  way it already read `max-batch-size`.
- **Config**: `bd-dispatcher { max-states-per-batch = 2000 }` added to
  `application.conf`, `docker-config.conf`, `ccad-config.conf` - comfortably above the
  ~700-750 states/batch observed in steady-state operation, far below any risk of
  repeating the 2.19M-state failure.

This bounds the one thing that actually matters (EclipseLink `UnitOfWork` size) via a
completely separate code path from the envelope cap, so it still holds even if whatever
defeated `max-batch-size` recurs.

## Verification

Full re-run of the exact original repro with the hardened fix (fresh image, fresh
Postgres volume, Prometheus/Grafana scraping throughout): 10 creatures died via the
mass-extinction burst around 1 minute in, backlog peaked ~2.2M, drained steadily
(`dl2l_bdactor_batch_size` consistently 685-785 the entire run, never approaching either
cap), memory held flat at ~2.21GiB throughout, and `dl2l_bdactor_queue_depth` reached
**0** with the holder still running - a complete clean drain of the exact scenario that
previously OOM'd, both before and after this follow-up fix.

## Residual risk

Because the original envelope-cap bypass was never reproduced under instrumentation,
there is residual uncertainty about whether some other, larger multiplier of the bypass
could still exceed `max-states-per-batch = 2000` in a worse-case scenario. This is judged
low-risk (the observed failure was ~4 orders of magnitude over the intended cap; 2000 is
itself ~3x above normal peak, and EclipseLink's cascade behavior - if that is indeed the
trigger - scales with the entities actually touched, not an independently-runaway
counter), but worth flagging: if `dl2l_bdactor_batch_size` or `dl2l_bdactor_queue_depth`
ever show sustained values far above ~2000/~a few thousand in production telemetry, that
is a real signal this residual risk materialized and needs the cascade-registration
angle investigated further (e.g., checking whether any long-lived entity's
`@OneToMany(cascade=ALL)` collection - `ChangeStimulusState.receivedStimuli`/
`emittedStimuli` are the two that exist today - is being re-persisted with a
never-shrinking Java-side list across many transactions).
