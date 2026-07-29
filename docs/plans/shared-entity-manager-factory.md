# Share one JPA EntityManagerFactory per JVM instead of one per component actor

## Context

The dispatcher-parallelism fix (PR #74, `docs/plans/ccad-dispatcher-parallelism-fix.md`)
reduced `component-dispatcher`'s thread pool from 18 to 6 threads, matching CCAD's
6-CPU-per-trial cgroup budget. Re-running the `20260728_tick_rate_diagnostic` experiment
against that fix, forced onto node c1 (the historically-affected node) via
`-e ccad_sim_exclude_nodes=c2,...,c11`, showed the stall was **not** resolved — and got
worse: every one of the 10 creatures in all 5 trials spent the majority of an 18-minute
run flat on `dl2l_creature_cognitive_cycles_total`, often in overlapping windows across
creatures (unlike the original staggered, per-creature-independent pattern). The same
run on node c11 (in the same round of testing) stayed healthy (~1.5-2 cycles/s, zero
stalls), ruling out a regression from the dispatcher fix itself and confirming this really
is node-c1-specific.

External noisy-neighbour jobs were ruled out (`sacct -a --nodelist=c1` showed only our own
5 array tasks during the run window). c1 and c11 are different hardware (c1: single-socket
48-core Xeon 6515P, no hyperthreading; c11: dual-socket 28-core E5-2660 v4 with
hyperthreading) but c1 is the *newer*, nominally faster chip, so raw clock speed doesn't
explain it either (confirmed live: all 48 cores steady at 2300MHz, no throttling).

Live inspection of a running trial on c1 (`srun --overlap` + `kill -QUIT` on the holder
PID, mid-run) found the holder JVMs pegged at ~90% CPU with load average 18.79 — high, not
low, unlike the original pre-dispatcher-fix stalls. Hitting the live metrics endpoint twice
15s apart confirmed 5 of 10 creatures were completely flat in that window while others
crawled at ~0.05-0.7 cycles/s (vs. c11's healthy ~1.5-2). A live thread dump caught multiple
`component-dispatcher` threads blocked in an identical stack:

```
br.cefetmg.lsi.l2l.creature.bd.JpaPersister.persist(JpaPersister.java:52)
br.cefetmg.lsi.l2l.creature.components.CreatureComponent.persist(CreatureComponent.java:85)
br.cefetmg.lsi.l2l.creature.components.Nose.onReceive(Nose.java:42)
...
org.eclipse.persistence.internal.sequencing.SequencingManager.acquireLock(...)
- parking to wait for <0x000000008148d648> (a ReentrantLock$NonfairSync)
```

Multiple threads were parked on the *exact same* lock object address, with one thread
actually doing a synchronous blocking Postgres round-trip
(`sun.nio.ch.SocketDispatcher.read0` under `QueryExecutorImpl.execute`) to fetch a new
JPA entity-ID sequence value.

Root cause: `ComponentActor.preStart()` called `JpaPersister::new` — and `JpaPersister`'s
no-arg constructor calls `Persistence.createEntityManagerFactory("L2LPU", ...)`
independently. Every component of every creature (Eye, Nose, Mouth, PartialAppraisal,
FullAppraisal, HomeostaticRegulation, Valuation, EmotionalSystemActor, EndocrineSystem,
NeuromodulatorSystem, SensoryCortex, EffectorCortex, Body — ~10 components x 10 creatures
= ~100 actors per holder) built its **own** `EntityManagerFactory` in `preStart()`, instead
of routing through the `BDActor`/`bd-dispatcher` architecture CLAUDE.md already documents
as the intended single-writer persistence path. ~100 separate factories per holder means
~100 separate JDBC connection pools against the same Postgres instance, and — evidenced by
the shared lock address in the thread dump — EclipseLink's sequence pre-allocation cache
ends up cold/fragmented across them rather than warm and shared, so far more persist()
calls than necessary fall through to a serializing, blocking DB round-trip. This directly
explains why the earlier dispatcher-parallelism fix made the *symptom* worse: shrinking the
pool from 18 to 6 threads means the same lock contention now blocks a much larger fraction
of the available pool at any given moment.

Same root problem, smaller instances, was also present in `CreatureActor` (own EMF in its
constructor, once per creature), `MemoryConsolidator`/`MemoryTraceConsolidator` (own EMF
per creature, in a field initializer), and `Holder`'s end-of-simulation `DataAnalyser` EMF.

## Fix

Add `PersistenceExtension` (`br.cefetmg.lsi.l2l.creature.bd.PersistenceExtension`), an Akka
Extension — the same per-JVM-singleton pattern CLAUDE.md already documents and
`MetricsExtension`/`MLServiceExtension` already use — holding one `EntityManagerFactory`
per JVM node. `EntityManagerFactory` is thread-safe per the JPA spec (unlike
`EntityManager`, which is not); every caller still gets its own `EntityManager` via
`entityManagerFactory().createEntityManager()`, just from a shared, warm factory instead of
a freshly-built one.

Updated call sites (`ComponentActor`, `CreatureActor`, `MemoryConsolidator`,
`MemoryTraceConsolidator`, `Holder`) to resolve the shared factory from
`PersistenceExtension.of(context().system())` instead of calling
`Persistence.createEntityManagerFactory(...)` themselves. `ComponentActor`'s now-dead
`persisterFactory` 2-arg constructor (unreferenced anywhere in src/main or src/test — tests
use `TestingCreature`, which bypasses `ComponentActor`/Akka entirely) was removed rather
than kept as an unused alternate path. `JpaPersister`'s own no-arg constructor (which builds
an independent factory) is left in place as a general-purpose fallback, since nothing calls
it anymore after this change — not removed to avoid narrowing `Persister`'s public API
beyond what's needed for this fix. `Main.java`'s `runExtractor()` EMF (a separate one-shot
CLI extraction path outside the actor system, different DDL properties) is untouched —
out of scope.

`BDActor`/`bd-dispatcher` itself is left as-is (currently unused dead code) — fully routing
persistence through message-passing to a dedicated actor is a larger architectural change
than this fix's scope; sharing one factory is the minimal, low-risk change that directly
addresses the confirmed mechanism (many independent, cold sequencing caches) without
touching any of the 14 `persist()` call sites across creature components.

## Verification

1. `mvn package` compiles clean; `mvn test` — all 204 tests pass, no regressions.
2. Local Docker Compose smoke test: confirm creature persistence still works end-to-end
   (state records created, no EntityManager/transaction concurrency errors) with the shared
   factory.
3. Re-run `20260728_tick_rate_diagnostic` on CCAD, forced onto node c1 again, and check
   whether the stall pattern shrinks or disappears relative to the pre-fix and
   dispatcher-only-fix runs — the real test, since this is a second attempt after the first
   fix (dispatcher parallelism) proved insufficient.

## Housekeeping

Feature branch + PR (Java source change), per established preference — not direct to main.
