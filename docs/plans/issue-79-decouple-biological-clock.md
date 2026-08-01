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
   ms (config-driven; see target-rate below).
2. **Reroute** it: instead of `partial.tell("")`, each tick triggers exactly one
   `updatePositioningAttribute()` on the `CreatureActor`'s **own thread** (via the TypedActor
   self-proxy, so it is enqueued on the actor's mailbox and safely reads mutable geometry
   state — the current lambda only does a `tell`, which is thread-safe, so this is the one
   new thread-safety point to get right). One tick → one positioning send → one perception
   round → one cognitive cycle → one `persistCycle` write.
3. **Decouple the setters**: `setPosition`/`setVisionFieldOpening`/`setVisionFieldPosition`/
   `setOlfactoryFieldRadius` update internal state only and **no longer** call
   `updatePositioningAttribute()`. All geometry changes within a cycle coalesce into the
   next scheduled send. Between ticks the creature is static (it moves only when it
   cognizes, and it cognizes only on a tick), so the coalesced send always carries accurate
   geometry — no staleness, and food/world-object positioning attrs (separate senders) are
   unaffected, so collision accuracy is unchanged.

Net: the cascade stops perpetuating itself; the clock is the sole driver at a bounded,
reproducible wall-clock rate. Metabolism, perception, cognition, and `persist()` production
all advance at exactly `TARGET_HZ` regardless of CPU speed — **the OOM cause (unbounded
production) and the original issue-79 goal (machine-independent biological time) fall out of
the same mechanism.**

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
