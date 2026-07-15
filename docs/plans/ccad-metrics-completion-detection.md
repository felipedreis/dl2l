# CCAD: replace log-grep completion detection with the Micrometer status endpoint

## Context

`ansible/roles/trial_runner_ccad/templates/run_trial.sh.j2` currently detects simulation
completion by tailing `dl2l-manager.err` (an NFS-mounted Singularity instance log) and
grepping for one of two hardcoded strings SimulationManager logs immediately before
`stopSimulation()`: `"All creatures dead in holder"` (natural end) or `"Maximum runtime
reached"` (forced timeout). A third signal — polling `singularity instance list` for the
holder disappearing — was tried as a faster crash short-circuit and just had to be removed
(2026-07-15, job 198) after it falsely declared a healthy, actively-processing holder dead
after ~90s and killed the trial. See `docs/plans/ccad-singularity-experiments.md` for the
full history of this detection mechanism's evolution.

Separately, `feat/observability-metrics` (merged to `main` as of today, commit `517c1a5` /
PR #66) added `MetricsExtension` — a per-JVM Akka Extension (same pattern as
`MLServiceExtension`) that runs a Micrometer/Prometheus registry and exposes it over
Akka HTTP at `:9091/metrics`. Currently it only publishes creature-level gauges (arousal,
via `PartialAppraisal`). This plan proposes using that same mechanism for a
simulation-lifecycle gauge, and querying it directly instead of grepping a log file.

## Why this is worth doing

The log-grep approach has an inherent weakness independent of the instance-list bug just
fixed: it depends on (a) Singularity flushing the instance log to NFS in a timely way, and
(b) the exact log message text never changing. A direct HTTP query to the manager's own
live process state has neither dependency — it's a synchronous request-response against
the actual source of truth, not indirect evidence. Given how much of this week was spent
chasing detection-mechanism flakiness (instance-list polling, holder's suppressed logger,
now log-flush timing), a structured status endpoint is a more durable foundation, not just
a one-off fix.

## Design

### 1. New simulation-lifecycle gauge in `SimulationManager`

At the two existing log call sites (`onReceive`'s natural-death path and the
forced-timeout path, both immediately before `stopSimulation()`), also set:

```java
MetricsExtension.of(context().system()).registry()
    .gauge("dl2l_simulation_running", 0);
```

(and the inverse, `1`, at `startSimulation()`). `MetricsExtension.Impl.registry()` is
already public, so no new method is strictly required — though a small
`setGauge(String name, double value)` overload without the per-creature `Tags.of("creature",
...)` dimension would match the existing `setGauge(name, creatureId, value)` convenience
method's style better than reaching into `registry()` directly from outside the extension.

### 2. Fix a port collision this surfaces

`MetricsExtension` hardcodes `0.0.0.0:9091`. On local/Pi docker-compose this is fine — each
role is a separate container with its own network namespace. On CCAD, all four roles
(manager/detector/holder/db) share the *node's* network namespace (the same reason
manager/detector/holder already use distinct fixed ports 2551/2552/2553) — so whichever
JVM's `MetricsExtension` binds `:9091` first wins, and the others fail silently (caught by
the existing `.exceptionally` handler, but it means holder's own per-creature arousal
gauges are likely already unreachable on CCAD today, since manager and holder are separate
JVMs both trying to claim :9091). Fix: make the port configurable
(`dl2l.metrics.port` config key, read the same way other per-role ports already are),
default unchanged at 9091, and give each CCAD role its own port in
`run_trial.sh.j2` (e.g. manager=9091, holder=9092). This is what we actually need queryable
for completion detection anyway (manager's status), so getting the manager one distinct
port is the concrete requirement; giving holder one too is a small addition that also fixes
its currently-silently-broken metrics export on CCAD as a side benefit.

### 3. `run_trial.sh.j2`: query instead of grep

```bash
curl -s http://localhost:9091/metrics | grep -q '^dl2l_simulation_running 0'
```

in place of the `tail | grep -E "All creatures dead|Maximum runtime"` check, same 5s
polling interval, same ~65 min ceiling. Land it as an **addition checked first, with the
log-grep kept as a fallback** for one validation cycle rather than an outright replacement
— the log-grep path is proven (job 196 ran a full 41 minutes on it); the metrics path is
not yet proven live on CCAD specifically (in particular, whether Akka HTTP binds/responds
promptly inside a Singularity instance the same way it does under `docker-compose`, which
hasn't been tested there yet). Drop the log-grep once a couple of real trials confirm the
two signals agree on timing.

### Non-goals

- No long-lived Prometheus/Grafana stack on CCAD. `docker-compose-observability.yml` is for
  persistent local/dev use; `run_trial.sh.j2` just needs one-shot `curl` polling, nothing
  scrapes CCAD trials on an interval.
- Not touching holder's own completion signal — `Holder.java`'s `logger.setLevel(SEVERE)`
  (pre-existing, unrelated to this session) already makes its own log output unusable for
  this purpose, and manager's status remains the authoritative signal by design; this only
  changes *how* we observe it.
- No changes to `trial_runner_local`/`trial_runner_pi` — both already have reliable
  completion detection via `docker compose ... wait`/healthchecks and don't share CCAD's
  "no docker wait, log lives on NFS" constraints.

## 4. Broader metrics: manager/detector/holder + business-oriented signals

`feat/observability-metrics` currently only instruments the creature-level layer
(`PartialAppraisal` → arousal, `NeuromodulatorSystem` → dopamine/serotonin/orexin,
`MemorySystemActor` → memory count) via `CreatureComponent`'s already-wired `metricsExt`
field. Nothing at the role/cluster level (manager, holder, detector) or the
population/scientific level is exposed yet. `dl2l_simulation_running` (§1 above) is the
minimum needed for completion detection; the rest below is broader operational and
scientific visibility the user asked to fold into the same effort, grouped by where it
hooks in and roughly ordered by how directly it would have helped debug something this
week.

**One correction to Main.java's current behavior**: `MetricsExtension.of(system)` is
already called unconditionally in `Main.java` — "starts the /metrics endpoint on every
node, regardless of role" — so manager, detector, and holder *all* already try to bind
`:9091` today. Locally/on Pi this is harmless (separate containers, separate network
namespaces). On CCAD it's a real, live bug: all four roles share one node's network
namespace, so only the first JVM to bind wins and the rest fail silently (caught by
`.exceptionally`, but means holder's own creature-level gauges are almost certainly
unreachable on CCAD *today*, unnoticed until now because nothing has tried to scrape CCAD
yet). The per-role port fix in §2 fixes this for real, not just for the new manager gauge.

### Manager (`SimulationManager`)

- `dl2l_simulation_running` (0/1) — §1, needed for completion detection.
- `dl2l_simulation_elapsed_ticks` (gauge) — cheap visibility into how far a run has
  progressed without waiting for it to finish; useful for spotting a stalled-but-not-dead
  simulation, which log-grep alone can't distinguish from "still fine, just slow."
- `dl2l_holders_registered` / `dl2l_holders_expected` (gauge) — the startup handshake
  (`handleRegister`/"Holders count achieved the expected value") currently only shows up as
  a log line; a gauge here would catch a stuck-in-startup trial (e.g. a holder that never
  registers) well before the ~65 min wait ceiling in `run_trial.sh.j2` times out.
- `dl2l_world_objects_created_total{type=...}` / `dl2l_creatures_created_total` (counters,
  set once at `startSimulation()`) — cheap sanity check that the run actually got the
  world-object/creature counts the `.conf` asked for.

### Holder (`Holder`)

- `dl2l_creatures_alive` (gauge, ++ in `handleCreateCreature`, -- in `handleRemoveObject`) —
  population over time. This is the single most useful new signal for the dense-world
  pain-death question that's been open all week: instead of reconstructing survival curves
  after the fact from `body_states.parquet`, a live gauge shows the die-off in real time
  during a trial, and `run_trial.sh.j2` could even use a sudden drop as an early red flag.
- `dl2l_creature_deaths_total{cause=...}` (counter) — `PartialAppraisal.checkDeath()`
  currently only checks *whether* `getMaxDriveArousal().getLevel() >= MAX_AROUSAL_LEVEL`,
  not *which* drive tripped it, before calling the parameterless `creature.kill()`. Getting
  a cause label means threading the dominant drive's name through `checkDeath()` →
  `kill()` (or recording it at the `checkDeath()` call site, right where
  `getMaxDriveArousal()` is already called) — a small, real code change, not just a metrics
  call, so scope it as its own step. This directly answers "is the dense 10-creature world
  killing creatures via pain vs. hunger vs. sleep deprivation" quantitatively instead of by
  manually inspecting `drives.parquet` after each run, which is exactly the manual
  archaeology this week's validation trials required.
- `dl2l_world_objects_remaining{type=...}` (gauge, decremented on
  `handleRemoveObject`/eaten) — food depletion over time, the natural alternative
  hypothesis to pain-death for why creatures might be dying early in a denser world.
- Persistence health: this week's postgres-overlay exhaustion (job 196) produced ~700K
  duplicate `DatabaseException`/`No space left on device` log lines over ~27 minutes before
  anyone noticed — a `dl2l_persist_errors_total` counter incremented wherever those
  exceptions are caught would have made the failure visible within seconds instead of
  requiring log archaeology after the fact. Flagged as **needs investigation before
  committing to it**, not a trivial addition: from this week's logs, the EclipseLink
  exceptions look like they may be propagating as uncaught exceptions through Akka's default
  supervision (restart/resume) rather than being caught by application code today, so adding
  a counter may require wrapping specific `persist()` call sites in try/catch first — worth
  doing given how much this exact failure mode cost this week, but scope the actual
  try/catch placement as a small separate investigation, not assumed to be a one-line
  `metricsExt.setGauge(...)` add like the others here.

### Detector (`CollisionDetectorActor`)

- `dl2l_quadtree_object_count` (gauge, from `collisionTree`) — cheap liveness/sanity signal
  distinct from manager's and holder's (confirms the detector itself is still tracking
  geometry, not just that its process hasn't exited).
- Lower priority than manager/holder above — mostly ops visibility, not a scientific
  question this project currently has open. Include only if the manager/holder gauges land
  cleanly first; not required for this plan's core goal.

### Cross-cutting / "business" metrics beyond what's already wired

- `dl2l_creature_action_total{action=...}` (counter, in `FullAppraisal` wherever an action
  is finally selected) — behavioral distribution (time spent WANDER vs EAT vs SLEEP vs
  ...) is exactly the kind of signal the memory-vs-world-model experiment line
  (`20260709_memory_vs_wm_v1`, `20260714_memory_vs_wm_dense_reposition`) already computes
  post-hoc from `actions.parquet`; exposing it live costs one counter increment per cycle.
- `dl2l_consolidation_batches_total` / `dl2l_engrams_consolidated_total` (counters, in
  `MemoryConsolidator`/`MemoryTraceConsolidator`, right where they already log at `.fine`
  after this week's verbosity pass) — directly relevant to the memory-consolidation
  conditions in the current experiment lineup.
- JVM/process metrics (heap, GC pause time, CPU) via Micrometer's built-in binders
  (`JvmMemoryMetrics`, `JvmGcMetrics`, `ProcessorMetrics`, `UptimeMetrics` —
  `binder.bindTo(registry)` in `MetricsExtension.Impl`'s constructor, a few lines, no new
  instrumentation call sites anywhere else needed) — free given the registry already
  exists, and this week's disk/log-volume pressure makes JVM-level headroom worth having
  visible without needing to reason about it from first principles again next time
  something looks slow.

### Phasing

Given the surface above is now large, land it in three passes rather than one:

1. `dl2l_simulation_running` only (§1–§3) — unblocks the CCAD completion-detection
   improvement, independently useful, small and low-risk.
2. Manager + holder population/business metrics (`elapsed_ticks`, `holders_registered`,
   `creatures_alive`, `creature_deaths_total{cause}`, `world_objects_remaining`,
   `creature_action_total`, consolidation counters) — the set with genuine scientific
   value for the current experiment lineup.
3. Persistence-error counter (needs the try/catch investigation above), detector gauge,
   and JVM binders — lowest urgency, do once 1–2 are proven stable.

## Files touched

- `src/main/java/br/cefetmg/lsi/l2l/cluster/SimulationManager.java` — gauges at
  `startSimulation()` and both `stopSimulation()` call sites (§1, §4 manager section).
- `src/main/java/br/cefetmg/lsi/l2l/cluster/Holder.java` — gauges/counters in
  `handleCreateCreature`/`handleRemoveObject` (§4 holder section).
- `src/main/java/br/cefetmg/lsi/l2l/creature/components/PartialAppraisal.java` — thread a
  death-cause label through `checkDeath()` (§4 holder section, deaths-by-cause).
- `src/main/java/br/cefetmg/lsi/l2l/creature/components/FullAppraisal.java` — action-choice
  counter (§4 cross-cutting section).
- `src/main/java/br/cefetmg/lsi/l2l/creature/ml/MemoryConsolidator.java`,
  `MemoryTraceConsolidator.java` — consolidation counters (§4 cross-cutting section).
- `src/main/java/br/cefetmg/lsi/l2l/cluster/CollisionDetectorActor.java` — quadtree gauge
  (§4 detector section, lowest priority).
- `src/main/java/br/cefetmg/lsi/l2l/metrics/MetricsExtension.java` — parameterize the bind
  port (§2); add JVM binders (§4 cross-cutting, phase 3).
- `ansible/roles/trial_runner_ccad/templates/run_trial.sh.j2` — add
  `--env METRICS_PORT=9091` (manager) / `9092` (holder) to the relevant `instance start`
  calls; add the `curl`-based check alongside the existing log-grep (§3).

## Verification

- `mvn package` compiles clean.
- For the completion-detection gauge (§1–§3): one CCAD validation trial with both detection
  paths active side by side, confirming they report completion within the same ~10s window,
  before removing the log-grep fallback.
- For phase 2/3 metrics: a local `docker-compose-observability.yml` run (Prometheus already
  wired there) is enough to confirm each new gauge/counter shows real, sane values over a
  short trial — no CCAD round-trip needed since these aren't used for control flow, only
  observability.

## Status

Drafted 2026-07-15, not yet implemented. Section 4 added same day per explicit request to
broaden scope beyond just the completion-detection gauge. Blocked behind confirming today's
instance-list fix (job 199) is solid — no sense adding new detection/metrics surface until
the current fix is known-stable. Get this plan reviewed before implementing per the
standard development cycle; given the phasing above, phase 1 could be implemented and
validated independently of phases 2–3 if the user wants to unblock CCAD sooner rather than
wait for the full scope.
