# Fix unbounded dispatcher parallelism causing CCAD cognitive-cycle stalls

## Context

`20260717_memory_vs_wm_dense_scarce` showed some CCAD nodes (c1, c2) with per-creature
cognitive-cycle rates 6-10x lower than others, correlated with survival outcomes. A follow-up
diagnostic (`20260728_tick_rate_diagnostic`, new `dl2l_creature_cognitive_cycles_total` +
JVM GC/CPU/thread probes added in PR #73) reproduced the stall live on node c1 and ruled out GC
pauses, elevated CPU%, thread blocking, and CFS bandwidth throttling as causes (`nr_throttled=0`,
`cpu.max` unlimited quota). Stalls are per-creature/per-actor and staggered, not node- or
JVM-wide-synchronized.

A live JVM thread dump (`kill -QUIT` on the stalled trial's holder PID, since `jstack` was
unavailable via `srun --overlap`) found:

- 18 `l2l-component-dispatcher` worker threads
- 18 `l2l-akka.actor.default-dispatcher` worker threads
- `system_cpu_count` (JVM-reported, from the new `ProcessorMetrics` probe) = **6.0**, matching
  the SLURM `--cpus-per-task=6` request (the JVM sees the cgroup-restricted count, not the wider
  `cpuset.cpus.effective=0-47` list found separately via `srun --overlap` cgroup inspection).

`6 cores × 3.0` (Akka's default `fork-join-executor` `parallelism-factor`) `= 18`, exactly
matching both observed thread counts. `component-dispatcher` in `application.conf` sets only
`mailbox-type` — no `executor` override — so it silently inherits `akka.actor.default-dispatcher`'s
unconfigured (built-in Akka default) fork-join sizing. That default factor of 3.0 is tuned for
I/O-bound dispatchers, where oversubscription helps keep cores busy while some threads block on
I/O. `component-dispatcher` runs `PartialAppraisal`, `FullAppraisal`, `HomeostaticRegulation`,
`Valuation`, `EmotionalSystemActor`, `MemorySystemActor`, `OperantConditioningActor` etc. — CPU-
bound appraisal/homeostasis computation with no I/O blocking in the hot path — so the 3x
multiplier adds contention without benefit.

Each trial runs 3 JVM roles (manager, detector, holder) sharing one 6-CPU cgroup slice, each
independently spinning up 18+18=36 dispatcher threads (on top of GC/compiler/IO/remote threads —
71 total OS threads observed for the holder role alone). With up to 5 trials landing on the same
48-core physical node, correlated bursts (e.g. all trials' 1Hz `l2l-scheduler` keepalive ticks
firing near-simultaneously, since trials start together) can produce short run-queue contention
windows exceeding real capacity — too brief for a 1-minute-smoothed `system_load_average_1m` or
10-second-interval CPU% polling to reliably catch, which reconciles the "no elevated load/CPU
observed during stalls" finding with a real but bursty oversubscription mechanism.

## Fix

Give `component-dispatcher` its own explicit, bounded `fork-join-executor` config instead of
silently inheriting `default-dispatcher`'s I/O-oriented 3x factor — sized for a CPU-bound
workload on a 6-core budget (`parallelism-min=2, factor=1.0, max=6`).

Also tighten `akka.actor.default-dispatcher` itself with an explicit override (currently
completely unconfigured, i.e. pure Akka defaults) — shared cluster/remote/system machinery, kept
slightly more headroom (`parallelism-min=2, factor=1.0, max=8`) than `component-dispatcher` since
it's foundational and used more broadly, but still far below the current 18.

`object-dispatcher` (already explicitly bounded, factor=2.0/max=20) and `collision-dispatcher`
(mailbox-type only, will inherit the now-tightened `default-dispatcher`) are left alone in this
PR — not directly evidenced as contributors to the observed stall, and `collision-dispatcher`
benefits automatically once `default-dispatcher` is fixed.

## Verification

1. `mvn package` compiles clean.
2. Local smoke run (`inventories/local`), confirm the holder still starts and simulates normally,
   `system_cpu_count`/thread counts sane.
3. Re-run the `20260728_tick_rate_diagnostic` experiment on CCAD, biased toward node c1 (the
   historically-affected node), and check whether `dl2l_creature_cognitive_cycles_total` stalls
   disappear or shrink — an intervention-and-remeasure test, not another passive correlation.

## Housekeeping

Feature branch + PR (Java source change — not direct-to-main, per established preference).
