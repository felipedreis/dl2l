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

## Phase B — Durable fix: couple the clock to wall-clock time (only if A insufficient)

Make metabolic / circadian / sleep advance proportional to **elapsed wall-clock time**, so
lifespan is invariant to cycle throughput and reproducible across hardware.

**Recommended design — dt-weighted advance (minimal, robust to jitter):**
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

*Alternative considered (drive metabolism only from a dedicated fixed-rate scheduler, reusing
the `CreatureActor.java:196` precedent):* fully decouples but requires moving the pacemaker
out of `onReceive` and loses the natural coupling to perception. Prefer the dt-weighted
approach unless the experiment shows scheduler-only pacing is needed.

### Verify Phase B
- `mvn package` + `mvn test`.
- Re-run the p79 experiment on this Mac; additionally verify **reproducibility**: two runs
  at deliberately different cycle rates (e.g. throttle one, or compare Mac vs a slower
  config) must yield the **same wall-clock lifespan / drive trajectories**. Extend the same
  report with a Phase-B section and a lifespan-vs-cycle-rate figure.

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
