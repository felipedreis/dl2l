# Issue #79 — Decouple the biological clock from cognitive-cycle count

## Context

The creature biological clock (hunger / circadian / sleep pressure) advances **once per
`PartialAppraisal.onReceive()` call — i.e. once per cognitive cycle — with no wall-clock
term**. After #76 (async persistence) and #78 (bounded BDActor batch) removed the blocking
persistence that used to throttle the perception→action loop, cognitive-cycle throughput
jumped ~10-20x. Because metabolism is tied to *cycle count*, hunger now advances ~10-20x
faster in real time, so creatures starve in ~10-35 s instead of the pre-#76 ~150 s
(p59 report baseline) — the "mass-extinction burst ~1 minute in" seen during #77 OOM
verification. It also makes lifespan non-reproducible across hardware (cycle rate varies
with load).

**Key mechanic (confirmed):** the *only lethal* drive is **hunger** — it accumulates
`DELTA`/cycle and is cleared solely by EAT (`HomeostaticRegulation.handleAdrenergic` →
`EmotionalSystem.regulate(HUNGER, delta)`). Sleep / tedium / cortisol accrue *and* clear
per-cycle, so their equilibrium *levels* are scale-invariant; only their wall-clock
*cadence* (episode spacing, circadian day length) speeds up with throughput. So restoring
lifespan is primarily about the hunger rate, while restoring realistic rhythm needs the
circadian/sleep/tedium rates rescaled too.

The user wants a **two-phase** approach: try the cheap constant-rescale first and check
whether creatures then live long enough (and accrue enough cognitive cycles) for learned
behaviour to emerge; only if that is insufficient, do the durable wall-clock coupling.

Decisions locked with the user:
- **Rescale scope (Phase A):** hunger + rhythm rates (not hunger-only).
- **Scale factor S:** measure the current cognitive-cycle rate empirically first, then set constants.

### Code map (from exploration)

- Pacemaker: `PartialAppraisal.tickMetabolicPacemaker()` (`PartialAppraisal.java:115-124`),
  batched flush `flushMetabolicBatch()` (`:245-256`, hunger = `DELTA × HOMEO_BATCH_SIZE`).
  Driven per `onReceive` (`:48-81`); cycle counter at `:60-62`.
- Circadian oscillator: `ActiveCircadianClock.tick()/driveRate()/phase()`
  (`ActiveCircadianClock.java:15-31`); interface `CircadianClock.java`;
  no-op `DisabledCircadianClock.java`.
- Sleep timing recorded in cycles: `FullAppraisal.updateSleepState()` (`FullAppraisal.java:271-298`),
  `SleepEpisode` inner class (`:316-341`); second per-cycle clock
  `memorySystem.tickDecisionCycle()` (`:186`).
- Metabolic drift lands in drives via `HomeostaticRegulation.handleAdrenergic/handleAdenosinergic/
  handleCholinergic` (`HomeostaticRegulation.java:116-159`).
- Only wall-clock hook in the creature tree: the 1 Hz keep-alive scheduler in
  `CreatureActor.init()` (`CreatureActor.java:196-201`) — precedent for Phase B.
- All constants live in `Constants.java` (classic Akka actors — no `Behaviors.withTimers`).

---

## Phase A — Easy fix: rescale the throughput-sensitive constants

### A1. Measure the current cognitive-cycle rate (to compute S)

Run a short baseline diagnostic on this Mac to read the per-creature cycle rate directly.
The probe already exists: `dl2l_creature_cognitive_cycles_total` (incremented every
`PartialAppraisal.onReceive`, `PartialAppraisal.java:60-62`) and born/dead timestamps
(`CreatureActor.java:117,227`).

- Reuse the existing diagnostic harness `experiments/20260728_tick_rate_diagnostic.yml`
  (single baseline condition, 5 trials) — or a p59-style 3-creature / food-depleting
  config — run locally via the ansible experiment playbook (UI on, per project rule).
- From the collected Parquet + metrics logs compute **current cycles/s** and current
  mean lifespan / cycles-to-death.
- Baseline target from the p59 report (pre-#76): **~150 s lifespan, ~4,600 cycles to
  death ⇒ old effective rate ≈ 30 Hz.** Compute `S = current_rate / old_rate`
  (expected ~10-20). Equivalently set `DELTA_new = MAX_AROUSAL_LEVEL / (L_target ×
  current_rate)` with `L_target ≈ 150 s` — this is the value we solve for; `S` is just
  `DELTA / DELTA_new`.

### A2. Rescale constants in `Constants.java`

Apply the single measured factor **S** uniformly to the per-cycle *rate* constants that
determine wall-clock lifespan and rhythm. Divide accrual/clearing rates by S; multiply the
cycle-denominated *period/window* by S so those durations stay constant in wall-clock:

| Constant | line | change |
|---|---|---|
| `DELTA` (hunger drift) | 8 | `/= S` (primary lifespan lever) |
| `BASE_SLEEP_DRIVE` | 67 | `/= S` |
| `CIRCADIAN_AMPLITUDE` | 69 | `/= S` |
| `CHOLINERGIC_DELTA` (sleep clearing) | 10 | `/= S` (keep balanced vs sleep accrual) |
| `BOREDOM_RISE_RATE` | 105 | `/= S` |
| `TEDIUM_IDLE_RATE` / `TEDIUM_OBSERVE_RATE` / `TEDIUM_WANDER_RELIEF` | 41-43 | `/= S` |
| `PAIN_IMMUNE_RATE` | 46 | `/= S` |
| `CIRCADIAN_PERIOD_TICKS` | 65 | `*= S` (keep circadian day ≈ constant in seconds) |
| `MIN_SLEEP_TICKS` | 71 | `*= S` (episode floor stays ~constant in seconds) |

**Deliberately left unscaled in Phase A** (documented as the reason this fix is "cheap but
fragile"): the per-cycle multiplicative decay time-constants — `OREXIN_DECAY`,
`CORTISOL_DECAY`, `DOPAMINE_DECAY`/`SEROTONIN_DECAY`, and the tick-count thresholds
`CORTISOL_STRESSOR_SUSTAIN_TICKS`, `DEPRIVATION_RPE_INTERVAL`, `HOMEO_BATCH_SIZE`. Their
*levels* self-equilibrate, and rescaling them re-derives the carefully tuned equilibria in
their comments (orexin gate at 50% pressure, cortisol resting equilibrium, etc.). Note the
residual wall-clock speed-up of these in the report.

Keep each edited constant's comment accurate (several cite tick-denominated half-lives).
Prefer expressing the change so the intent is legible — keep the raw numbers but add a
one-line note that they were rescaled for issue #79 by factor S=<measured>.

### A3. Verify Phase A

1. `mvn package` — must compile clean; `mvn test` (update `CircadianClockTest` expectations
   if it asserts on `CIRCADIAN_PERIOD_TICKS`).
2. Mini-experiment (dev-cycle step 5): spec at
   `experiments/p79_metabolic_rescale.yml` (p59-style: 3 creatures, food depletes,
   all subsystems on), run via
   `cd ansible && ansible-playbook -i inventories/local run-experiment.yml -e experiment=p79_metabolic_rescale`.
3. Analysis at `analysis/experiments/p79_metabolic_rescale.py` (using `dl2l_analysis`)
   + report `docs/reports/p79_metabolic_clock_report.md` (Purpose/Assumptions/Hypothesis/
   Results/Analysis).
   **Success criteria:**
   - Mean lifespan restored to ≈150 s (± ~30 s), deaths from **hunger** not sleep.
   - Cycles-per-lifetime ≥ pre-#76 (~4,600) — i.e. **enough cognitive cycles for
     behaviour to emerge** (the user's gate). Since rate is higher and lifespan restored,
     this should comfortably exceed 4,600.
   - Behaviour-emergence signal present: e.g. EAT/APPROACH share rises over lifetime, or
     operant/action-tendency learning shifts action probabilities — sanity-check that
     learning has room to act within a lifetime.

**If Phase A passes → stop here.** If lifespan/rhythm is right but behaviour still fails to
emerge, or cross-run reproducibility remains a blocker, proceed to Phase B.

---

## Phase B — Durable fix: couple the clock to wall-clock time

**Revised after concrete evidence, see docs/plans/parquet-write-path.md's "Local
validation log".** The dt-weighted design originally written below (keep cycle rate
uncapped, just scale each tick's metabolic contribution by elapsed real time) was
**disproved empirically, not just theoretically**: validating the issue-79 Parquet/DuckDB
write-path pivot, a single uncontended local creature drove `dl2l_bdactor_queue_depth`
from 0 to 3.8M in under 100 seconds (~38K states/sec) and OOM'd a 2GB heap in ~2 minutes.
Confirmed via code search (`grep` for `scheduleAtFixedRate`/`scheduleWithFixedDelay`/
`scheduler().schedule` across the whole app — only `SimulationManager`/`GUIActor`, neither
in the per-creature path) and by tracing the message cascade end-to-end
(`Body`/movement → `CollisionDetectorActor.checkCreatureCollisions` (triggered by
receiving a `CreaturePositioningAttr`) → `Eye`/`Nose`/`Mouth`/`Body` stimuli →
`SensoryCortex` → `PartialAppraisal`/`FullAppraisal` → action selection →
`EffectorCortex` → movement → repeat): **there is no throttle anywhere in this loop.** It
runs exactly as fast as the CPU/dispatcher allows. dt-weighting only changes *how much*
each tick contributes to metabolism — it does nothing to *how many* ticks (and therefore
`persist()` calls) happen per second, so it doesn't touch the actual crash.

**Revised design — fixed-rate scheduler gates the cycle itself, not just metabolism:**
Introduce real wall-clock pacing at the point a new cycle begins, rather than dt-weighting
an uncapped one. This unifies two previously-separate concerns (reproducible biological
time; bounded write volume) behind one mechanism, and is well-grounded rather than
arbitrary: the pre-#76 system already ran at **~30 Hz** effective rate (p59 baseline,
~150s lifespan / ~4,600 cycles) and Phase A's own success criteria already demonstrated
that rate is *sufficient* for behaviour emergence — so capping back near there isn't a new
constraint, it's restoring the rate the whole system (including Phase A's rescaled
constants) was implicitly calibrated against, while keeping #76/#78's real win (cognition
no longer *blocks* on synchronous persistence I/O).

### Loop trace (completed 2026-08-02 — the design rests on this)

One **cognitive cycle** = one `PartialAppraisal.onReceive` (`PartialAppraisal.java:48`):
increments `dl2l_creature_cognitive_cycles_total`, ticks metabolism/neuromodulators/
endocrine, builds the `EmotionalStimulus` → `FullAppraisal` → action selection → effector,
and calls `persistCycle()` — **the write to `BDActor` happens here, once per cycle.**

That `onReceive` fires because perception stimuli arrived from `SensoryCortex`, which exist
only because the creature told the collision detector its geometry via
`updatePositioningAttribute()` (`CreatureActor.java:265`). That method is the **sole
loop-closer**: it is called only by the four movement/perceptual-field setters —
`setPosition` (`Body.java:36`, MOVE), `setVisionFieldOpening`/`setVisionFieldPosition`
(`Eye.java`, LOOK/eye-close), `setOlfactoryFieldRadius` (`Nose`). Each currently fires a
**full perception round immediately**, so one decision touching several setters spawns
several perception rounds, and the cascade self-perpetuates as fast as the dispatcher
allows — **this is the unbounded producer that fills the mailbox and the heap.** The
collision detector generates perception *only* on receiving a positioning attr
(`CollisionDetectorActor.java:68-73`); nothing else re-triggers it. So gating that one send
gates the entire cascade. `CreatureActor` already owns a wall-clock scheduler
(`CreatureActor.java:191-196`) — a 1 Hz keep-alive that `tell`s `PartialAppraisal` an empty
string (a metabolism-only tick, no perception). That is the precedent to repurpose.

### Design — scheduler-driven perception (RESOLVED; user chose "CreatureActor pacemaker")

1. **Retune** the existing `clock` scheduler from a hardcoded 1000 ms to `1000 / TARGET_HZ`
   ms.
2. **Reroute** it through a new `Creature.tick()` (dispatched via the TypedActor self-proxy,
   captured in `init()` while running on the actor's own thread, so it's safe to invoke from
   the scheduler's thread - calls through it land on the actor's mailbox like any other
   message). `CreatureActor.tick()` does **two** things, not one:
   - **Sends the direct heartbeat** to `PartialAppraisal` (`componentRef(PartialAppraisal.class)
     .tell("", ...)`) - this is the *same unconditional send* the old 1Hz scheduler made,
     just now firing at `TARGET_HZ` instead of 1Hz. **Caught mid-implementation (by the
     user) that dropping this would have been a real regression**: perception alone cannot
     guarantee a cycle fires, because `SensoryCortex.onReceive` only calls
     `creature.partialAppraisal().tell(...)` inside a loop over received stimuli - if
     nothing is nearby, that loop runs zero times and `PartialAppraisal.onReceive` never
     fires at all. Without the heartbeat, a creature alone in empty space would never
     metabolize, never check death, never act - frozen, not bounded.
   - **Calls `updatePositioningAttribute()`** - broadcasts this tick's position, which
     asynchronously (and only if something is actually nearby) triggers the collision
     detector to send perception stimuli, producing its own separate cycle(s) on
     `PartialAppraisal`. This was always decoupled in timing from the heartbeat (the round
     trip can cross nodes) - Phase B only changes *how often a new broadcast is triggered*
     (once per tick, not once per movement), not this pre-existing asynchrony.

   So one tick is **not** exactly one cognitive cycle - it's *at least* one (the heartbeat,
   guaranteed) *plus possibly more* (perception-driven, bounded by how many sensors have
   something to report, small and non-recursive since step 3 below means none of them
   re-trigger another broadcast). This mirrors the pre-Phase-B system's actual structure
   (1Hz heartbeat + independent, much-more-frequent perception-driven cycles) - Phase B caps
   the *broadcast* rate, not the perception-driven cycle count directly.
3. **Decouple the setters**: `setPosition`/`setVisionFieldOpening`/`setVisionFieldPosition`/
   `setOlfactoryFieldRadius` update internal state only and **no longer** call
   `updatePositioningAttribute()`. All geometry changes within a cycle coalesce into the
   next scheduled send. Between ticks the creature is static (it moves only when it
   cognizes, and it cognizes only on a tick), so the coalesced send always carries accurate
   geometry — no staleness, and food/world-object positioning attrs (separate senders) are
   unaffected, so collision accuracy is unchanged. **This step is what actually kills the
   unbounded recursion** (no setter re-triggers a broadcast), independent of exactly how
   many onReceive calls one tick produces.

Net: the cascade stops perpetuating itself; the clock is the sole driver of new broadcasts,
at a bounded, reproducible wall-clock rate. Cycle count per creature is now bounded by
`TARGET_HZ × (1 + small constant)` instead of unbounded - **the OOM cause (unbounded
production) and the original issue-79 goal (machine-independent biological time) fall out of
the same mechanism.** (Revise the write-throughput sanity check below accordingly - it
assumed exactly 1 cycle/tick, which is now a lower bound, not exact.)

**Fallback if this approach hits trouble in validation (user's suggestion, recorded
2026-08-02):** debounce at `CollisionDetectorActor.checkCreatureCollisions` instead - a
per-creature last-processed timestamp; skip the collision query + stimulus emission (the
actual expensive, write-generating work) if called again before `1/TARGET_HZ` has elapsed,
while still cheaply updating `creatureAttrs` bookkeeping every call. Leaves `CreatureActor`'s
setters and the original 1Hz heartbeat **completely untouched** - no `Creature.tick()`, no
self-proxy capture, no risk of the "no perception ⇒ no cognition" regression above, since
nothing about how cognition gets triggered changes at all. Smaller, more contained diff (one
method, one file) at the cost of two throttle points existing conceptually (broadcast is
still unbounded in message count, only the expensive downstream work is rate-limited) rather
than one clean gate. Worth trying if the CreatureActor-pacemaker approach shows problems in
PB5's validation.

**dt-weighting kept as the within-tick correction.** Even a fixed-rate scheduler jitters
(we just watched ticks slip 2-13 s under the livelock). So still weight each cycle's
metabolic advance by the *actual* elapsed `dt` (original design below), not an assumed
`1/TARGET_HZ`. Division of labour: the rate cap bounds *how many* cycles/s (fixes the OOM);
dt-weighting makes *each* cycle's biological advance accurate (fixes reproducibility
precisely, robust to jitter).

**Target rate — 30 Hz starting point, confirm by measurement (user chose "measure first").**
30 Hz is the pre-#76 effective rate, already shown *sufficient* for behaviour emergence and
*survivable*. Write-throughput sanity check: 30 Hz × 10 creatures = 300 cycles/s; one cycle
emits far fewer than `38 000/30 ≈ 1266` states, and `ParquetBackend` cleanly absorbed one
creature's full ~38 K states/s, so aggregate sits comfortably under the proven write ceiling.
The acceptance gate (below) measures `queue_depth` directly under the cap and we tune if
needed. Make `TARGET_HZ` a `Constants`/simulation-config value so it's tunable without a
rebuild.

**Phase A constants — re-derive together, don't layer (user chose this).** With cycle rate
pinned near the ~30 Hz the Phase A constants were implicitly calibrated against, Phase A's
`S`-rescale (S ≈ 10-20) largely unwinds (S → ~1). Treat cap + constants as one calibration:
under the cap, re-measure lifespan on the capped build and set `DELTA` so `L_target ≈ 150 s`
at `TARGET_HZ` (equivalently, re-express the per-cycle rate constants as per-second rates ×
`dt`). Do **not** stack Phase A's ×S on top of the cap.

**Risks to close in the mini-experiment (not just build-green):**
- Some behaviour may implicitly rely on the tight self-perpetuating cascade (a "decision"
  spanning several immediate perception rounds). Coalescing to one round per tick could
  shift dynamics — behaviour-emergence metrics are part of the gate, not an afterthought.
- The TypedActor self-invocation must go through the proxy, not the raw object — verify the
  self-proxy handle is available at scheduler-setup time in `init()`.

*(Original dt-weighted-only design, kept for reference — no longer the plan on its own:)*
- Add a `long lastTickNanos` timestamp to `PartialAppraisal` (init in `preStart`).
- In `tickMetabolicPacemaker()` (`PartialAppraisal.java:115`), compute
  `dt = (now - lastTickNanos) / 1e9` seconds each call and weight every per-time term by
  `dt`: hunger drift `DELTA_PER_SEC × dt`, sleep drive `driveRate_per_sec × dt`. Keep the
  existing `HOMEO_BATCH_SIZE` flush purely as a *message-rate* optimisation (accumulate the
  dt-weighted deltas, flush the running sum every N cycles / on wake).
- Change `CircadianClock.tick()` to `tick(double dtSeconds)` and advance
  `phase += 2π / CIRCADIAN_PERIOD_SECONDS × dt` in `ActiveCircadianClock`
  (`ActiveCircadianClock.java:15-21`); `DisabledCircadianClock` ignores `dt`.
- Reinterpret the rescaled constants from Phase A as **per-second** rates and rename/comment
  accordingly (`CIRCADIAN_PERIOD_TICKS` → a seconds-based period). Sleep-episode durations
  and `MIN_SLEEP_TICKS` become wall-clock (seconds) — record `SleepEpisodeState` onset/dwell
  in ms as well as cycles (`FullAppraisal.java:279-291`); the memory clock
  `memorySystem.tickDecisionCycle()` (`FullAppraisal.java:186`) can stay cycle-based (it is
  a learning cadence, not a biological clock) — call that out explicitly.

### PB4 implementation (completed 2026-08-02): unwound analytically, not re-measured

Ran `p79_single_creature_diag.conf` (1 creature, 1000 food objects in an 800x600 world -
the throwaway diagnostic conf from the earlier write-path work) under the finished PB1-3
build to get a real lifespan number. Found two things:

1. **Cognitive-cycle rate under the cap is ~300Hz, not ~30Hz**, because this diagnostic's
   food density is unusually high - every tick, many objects are in sensory range, and
   `SensoryCortex` forwards each perceived stimulus as its own separate `.tell()`, so a
   single tick's `updatePositioningAttribute()` can produce many separate perception-driven
   `PartialAppraisal.onReceive` calls (bounded, not recursive - see the `Creature.tick()`
   correction above - but a much bigger constant than assumed). Documented as a caveat on
   `Constants.TARGET_CYCLE_HZ`.
2. **This doesn't actually matter for DELTA's calibration**, and is why: dt-weighting
   (PB3) makes the *sum* of all cycles' contributions over any wall-clock window equal
   `DELTA × TARGET_CYCLE_HZ × window_seconds`, *regardless of how many onReceive calls
   happened in that window* - each call's `cycleEquivalent = dt × TARGET_CYCLE_HZ` shrinks
   exactly enough to compensate for firing more often, since `Σ dt` over a fixed window is
   always that window's duration. So the 300Hz-vs-30Hz question is irrelevant to lifespan;
   only `TARGET_CYCLE_HZ` (the constant, not the actual call rate) matters.

Given (2), a live re-measurement in this specific dense/atypical world was actually the
*wrong* calibration tool (confirmed: a raw 74s two-point slope on the `dl2l_creature_arousal`
gauge extrapolated to ~410s lifespan, order-of-magnitude consistent with but noisier than the
analytical answer, muddied by sample-timing imprecision and this world's high eating
frequency). The clean approach: since `TARGET_CYCLE_HZ=30` was deliberately chosen to match
the pre-#76 baseline rate Phase A's `S` was originally computed against, **Phase A's rescale
can be unwound analytically** - multiply/divide each dt-weighted constant by `S=5.858` to
recover its pre-#76 original value:

| Constant | Phase A value | × or ÷ S | Unwound | Used |
|---|---|---|---|---|
| `DELTA` | 2.56067e-4 | × | 1.50004e-3 | **1.5e-3** |
| `BASE_SLEEP_DRIVE` | 1.70711e-4 | × | 1.00003e-3 | **1.0e-3** |
| `CIRCADIAN_AMPLITUDE` | 8.53555e-5 | × | 5.00013e-4 | **5.0e-4** |
| `CIRCADIAN_PERIOD_TICKS` | 1172 | ÷ | 200.07 | **200** |
| `MIN_SLEEP_TICKS` | 59 | ÷ | 10.07 | **10** |

Every one lands within 0.1% of a clean round number - strong corroborating evidence these
*are* the genuine pre-#76 originals (not coincidence), and that `S=5.858` was accurately
derived. Applied in `Constants.java`; `mvn test` still 228/228 green (nothing hardcoded the
old values).

**KNOWN GAP, deliberately not fixed here**: `TEDIUM_IDLE_RATE`/`TEDIUM_OBSERVE_RATE`/
`TEDIUM_WANDER_RELIEF`/`PAIN_IMMUNE_RATE` were also Phase-A-rescaled but are consumed via
`HomeostaticRegulation.handleTedium`/pain-immunity paths that fire per action-selection
event, not per dt-weighted pacemaker cycle - PB3 never dt-weighted them, so unwinding their
values without a dt-weighting mechanism to back it up would just guess. Left at their Phase A
values; still call-rate-sensitive (same "re-breaks if throughput shifts" caveat Phase A
always had). Follow-up if PB5 or later validation shows tedium/pain dynamics are off.

### Verify Phase B
- `mvn package` + `mvn test`.
- Re-run the p79 experiment on this Mac; additionally verify **reproducibility**: two runs
  at deliberately different cycle rates (e.g. throttle one, or compare Mac vs a slower
  config) must yield the **same wall-clock lifespan / drive trajectories**. Extend the same
  report with a Phase-B section and a lifespan-vs-cycle-rate figure.
- **New, given the actual motivation this time**: re-run the write-path validation
  (`p79_single_creature_diag.conf` or similar) under the rate cap and confirm
  `dl2l_bdactor_queue_depth` stays bounded/near-zero throughout — this is the real
  acceptance gate, not just lifespan/reproducibility.

### PB5 core acceptance gate: PASSED (2026-08-02)

Re-ran the exact scenario that OOM'd every single time earlier this session -
`20260717_memory_vs_wm_dense_no_reposition_1_baseline.conf` (10 creatures),
`PERSISTENCE_BACKEND=parquet` - against the finished PB1-4 build.

**Result: clean, full, natural completion. No OOM, no livelock, no crash.**
- All 3 containers (manager/detector/holder) exited **code 0**.
- All **10/10 creatures died naturally** (`creature_state.parquet` has exactly 20 rows -
  10 births + 10 deaths, matching `ParquetBackend`'s documented no-upsert duplicate-row
  design) over a ~382s run - longer than the pure-starvation `DELTA` calibration's ~150s,
  consistent with creatures eating to extend lifespan and this scenario's food-scarcity
  (`reposition=false`) dynamics.
- `dl2l_bdactor_queue_depth`, polled every 30s for the full run: **flat at 0 almost the
  entire time**, with two small, self-resolving blips (8296 at t=159s, back to 0 by t=191s;
  1998 at t=223s, back to 0 by t=255s) - nothing resembling the six-to-seven-figure runaway
  growth of every prior crash (compare: 1.24M peak on the pre-Phase-B DuckDB run, 3.3M+ on
  the row-group-tuned-but-pre-Phase-B Parquet run).
- Holder RSS held flat at **~1.3-1.4GiB** the entire run (`docker stats`) - nowhere near the
  2GB `-Xmx` ceiling every previous crash pinned against.
- CPU dropped from ~650% to ~370% to ~30% over the run's final third, tracking creatures
  dying off one by one - the expected shape, not a stall signature.
- All 22 raw output tables present with substantial, real data (`stimulus_state.parquet`:
  20,765,340 rows, 1.5GB; every other table non-empty and appropriately sized) - the write
  path produced a complete, usable dataset, not just "didn't crash."

**Not yet covered by this run** (genuine remaining PB5 scope, follow-up):
reproducibility across host speeds, and a full behaviour-emergence report (p79-report-style,
Purpose/Assumptions/Hypothesis/Results/Analysis) comparing pre- and post-Phase-B dynamics.
The core, most consequential acceptance criterion - the exact original-crash scenario now
survives cleanly end-to-end - is confirmed.

### CCAD re-run (2026-08-02): OOM reproduced — Phase B's fix is CPU-headroom-dependent, not unconditional

Re-ran the same scenario for real on CCAD (`p79_ccad_baseline_validation`, image
`ghcr.io/felipedreis/dl2l:issue-79-phase-b`), the original crash site (jobs 519/520,
cancelled earlier this session). Two infra bugs fixed along the way (both committed,
unrelated to Phase B itself):
- CCAD's login node `/tmp` is a tiny 6.3G root filesystem (~1.6G free) that
  `singularity pull`/`build` stage into by default; the larger post-write-path image blew
  through it. Fixed: `SINGULARITY_TMPDIR`/`CACHEDIR` redirected to `$HOME/l2l` (973G free)
  - must be an absolute path (a relative one fails differently: "no parent mount point
    found").
- `/dl2l/heapdumps` was never bound to anything, landing on the instance's ephemeral
  `--writable-tmpfs` overlay - a `HeapDumpOnOutOfMemoryError` attempt there both competes
  with the JVM heap for the same node's RAM and is unrecoverable after the instance stops.
  Fixed: bound to node-local `/scratch` (same rationale as `SAVE_DIR`).

**With both infra fixes in place, the OOM reproduced anyway - twice (jobs 521 and 523),
same ~7-8 minute timing both times.** Retrieved the heap dump + GC log this time (job 523,
via `srun --jobid=<id> --overlap` to reach node-local `/scratch` from the login node - not
visible from the login node's own filesystem). GC log shows the unmistakable, now-familiar
signature: **1297 consecutive "Pause Full" cycles**, heap pinned at `2046M/2048M`, `Old
regions: 1980->1980` unchanged across all of them - reclaiming nothing, for over 13 minutes
of wall-clock time, still ongoing when cancelled.

**Root cause hypothesis (strong, not yet conclusively proven): CPU headroom, not the rate
cap.** The GC log shows G1 `"Using 6 workers of 6"` for every full compaction - exactly
matching CCAD's SLURM allocation (`scontrol show job`: `NumCPUs=6, CPUs/Task=6`, one trial
per node, not contending with its sibling). My Mac's local validation ran with ~9-10 cores
available (`docker stats` showed CPU% up to 900%+). G1's parallel-GC reclaim throughput
scales with available cores; with only 6, the same "Mark live objects" phase that took
~1.1-1.2s locally likely takes proportionally longer here, and if `BDActor`'s own Parquet
write throughput is similarly CPU-bound and core-limited, the same nominal
`TARGET_CYCLE_HZ=30` production rate - safely under the write/GC capacity on a beefier
local machine - can still outpace it when squeezed onto 6 cores.

**What this means**: Phase B's fix is real and directionally correct (confirmed: it turns
an *unbounded* producer into a *bounded* one), but "bounded" was implicitly validated
against local hardware's CPU headroom, not proven independent of it. CCAD - the actual
original crash site issue #79 was trying to fix - still OOMs at the same 10-creature scale
under its own real resource constraints. This is a genuine, unresolved gap, not a
documentation nit: **the local PB5 "PASSED" result does not, by itself, mean CCAD is fixed.**

**Options going forward (not yet decided):**
- Lower `TARGET_CYCLE_HZ` specifically for CPU-constrained deployments (trades behavioural
  fidelity/throughput for safety margin under fewer cores) - would need to be
  config/env-driven, not the current hardcoded `Constants` value.
- Request more CPUs per CCAD trial in the SLURM submission (if the cluster has the
  capacity) to close the core-count gap with local instead of changing app behavior.
- Reduce creature count for CCAD-scale validation as a stopgap while the above is decided.
- Investigate whether `BDActor`'s Parquet write throughput specifically (not just G1 GC) is
  the more core-sensitive half of this - not yet isolated from the GC evidence alone.

---

## Files to modify

- `src/main/java/br/cefetmg/lsi/l2l/common/Constants.java` — Phase A rescale (+ Phase B
  per-second reinterpretation).
- (Phase B) `src/main/java/br/cefetmg/lsi/l2l/creature/components/PartialAppraisal.java`
  (`tickMetabolicPacemaker`, `preStart`), `.../ActiveCircadianClock.java`,
  `.../CircadianClock.java`, `.../DisabledCircadianClock.java`,
  `.../FullAppraisal.java` (sleep-episode timing), and
  `src/test/java/.../CircadianClockTest.java`.
- New: `experiments/p79_metabolic_rescale.yml`,
  `analysis/experiments/p79_metabolic_rescale.py`,
  `docs/reports/p79_metabolic_clock_report.md`.

## Process notes
- Java/src changes go on a branch + PR (report/experiment/analysis artifacts can accompany).
- Local mini-experiments run with UI on.
- Experiment data uploads to HF automatically (`upload.prefix: p79/`); do not disable.
