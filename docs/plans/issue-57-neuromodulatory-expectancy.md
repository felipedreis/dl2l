# Issue #57 (repurposed) — Neuromodulatory expectancy loop: symbolic dopamine/serotonin RPE learning

## Context

Issue #57 began as "emotion-conditioned action selection" (add `affectBasic` to the operant table).
The design discussion (this conversation) concluded that the narrow fix sits on top of a deeper,
missing mechanism and should be **repurposed** into it:

- DL2L's learning signal is currently a **discarded boolean** — `Valuation` computes
  `valence = arousalVariation < 0` and calls `operantConditioning.varyProbability(type, action, 1, valence)`,
  throwing away the outcome *magnitude* (`OperantConditioningActor.java:28`, `Valuation.java:53-60`).
- ARTÍFICE's **expectancy** value is gone entirely; **valence** survives only as that boolean. Neither
  is used to compute a **reward-prediction error (RPE)**.
- Neuromodulators are already modelled correctly for one molecule: `PartialAppraisal` emits a tonic,
  circadian-coupled `AdenosinergicStimulus` each cycle (`PartialAppraisal.java:54-60`) that
  `HomeostaticRegulation` integrates into the `sleep` level. Adenosine is the template for a general
  **leaky neuromodulator pool**; DA/5-HT have never been added.

**Goal.** Close the associative-learning core by building the full active loop: a dedicated **symbolic
expectancy predictor** → **RPE** → **dopamine (phasic teaching + tonic exploration)** and
**serotonin (tonic satiety/patience)** neuromodulator pools → graded-valence learning + behaviour
modulation. Ship **two symbolic expectancy variants** and compare them in an experiment:
- **DISCRETE** — keyed on `(dominantDrive, target, action)` (magnitude-blind; the original #57 idea, now a predictor).
- **CONTINUOUS** — keyed on `(dominantDrive, driveLevelBucket, target, action)` (captures "how hungry").

Everything ships behind `LearningSettings` flags, **default-off**, so the current baseline is unchanged
and arms are A/B-comparable. This supersedes the previous
`docs/plans/issue-57-emotion-conditioned-action-selection.md` and PR #62.

## Sign convention

`reward = -arousalVariation` (positive = drive dropped = good). Predictors estimate **expected reward**.
`rpe = reward - expected`. Dopamine phasic release ∝ `rpe`. `actual` is the **raw** reward — no
drive-scaling — so the magnitude signal is exactly what the CONTINUOUS predictor can capture and the
DISCRETE one cannot (that gap *is* the experiment).

---

## Architecture

```
PartialAppraisal (pacemaker, per cycle)
  ├─ AdrenergicStimulus / AdenosinergicStimulus            (unchanged)
  ├─ SerotonergicStimulus(satiety(activeDrives)) ──► NeuromodulatorSystem   ← tonic 5-HT release
  └─ NeuromodulatorTick(circadianPhase)          ──► NeuromodulatorSystem   ← decay + circadian baseline

World → Mouth → Nutritive/Nociceptive/... → HomeostaticRegulation
  └─ EvaluationStimulus(target, action, regulatedEmotion, arousalVariation, ctxSnapshot) → Valuation
                                                         ▲ NEW: decision-time state snapshot for keying

Valuation (VTA/SNc + striatum)
  reward   = -arousalVariation
  expected = expectancy().expected(ctx, target, action)      ← expectancy stays a TypedActor facade (pure computation)
  rpe      = reward - expected
  expectancy().observe(ctx, target, action, reward)          ← learn predictor
  DopaminergicStimulus(rpe, target, action) ──► NeuromodulatorSystem   ← phasic DA as an explicit message
  operantConditioning.varyProbability(target, action, |rpe|, rpe>0)   ← graded valence (magnitude used)
  memory.reinforceWarmTraces(rpe, cycle)                     ← RPE-gated consolidation
  persist(ExpectancyState{...})                              ← experiment data

NeuromodulatorSystem (untyped CreatureComponent — VTA/raphe pools)
  onReceive(DopaminergicStimulus)  → dopamine  += rpe        (leaky integrator; track lastPhasic)
  onReceive(SerotonergicStimulus)  → serotonin += satiety
  onReceive(NeuromodulatorTick)    → conc = conc*DECAY + baseline(circadianPhase)
  after any update ──► NeuromodulatorState(daTonic, serotoninTonic) to FullAppraisal   ← published, not queried

FullAppraisal (behaviour modulation, when neuromodulationEnabled)
  caches latest NeuromodulatorState (eventually-consistent; tonic levels are slow-varying)
  daTonic       → ActionProbabilityFilter sampling temperature + RandomFilter weight (exploration)
  serotoninTonic→ TargetDistanceFilter patience + rest/observe bias
```

The neuromodulator pool is an **untyped actor** (a `CreatureComponent` under `ComponentActor` /
`component-dispatcher`, batched `onReceive` — the same runtime as `Mouth`, `HomeostaticRegulation`).
Neuromodulators are delivered **only as explicit `Stimulus` messages** (`DopaminergicStimulus`,
`SerotonergicStimulus`), never synchronous method calls — the molecule *is* the message. The tonic
concentration is a genuine leaky **integral of release quanta**, and it reaches consumers by being
**published** as a `NeuromodulatorState` message (no cross-actor synchronous read).

---

## Implementation (staged; each stage compiles + tests green)

### Stage 1 — Symbolic expectancy predictor (pure, no wiring)
New package `creature/conditioning/expectancy/`:
- `ExpectancyPredictor` (interface): `double expected(ExpectancyContext, WorldObjectType, ActionType)`;
  `void observe(ExpectancyContext, WorldObjectType, ActionType, double reward)`.
- `ExpectancyContext` — carries `dominantDriveName` and `dominantDriveLevel` (extensible).
- `DiscreteDriveExpectancy` — `Map<(drive,target,action) → RunningMean>`; Rescorla-Wagner update
  `v ← v + α(reward − v)`.
- `ContinuousDriveExpectancy` — key adds `bucket(dominantDriveLevel)` over `[MIN_AROUSAL_LEVEL, MAX_AROUSAL_LEVEL]`
  into `Constants.EXPECTANCY_LEVEL_BUCKETS` bins; same running-mean update per bucket.
- Unit tests: DISCRETE cannot distinguish two levels (same prediction) while CONTINUOUS does; both
  converge to the mean reward per key; unseen key returns a neutral prior.

### Stage 2 — Neuromodulator pool as an untyped component + its stimuli
- New stimuli in `stimuli/`: `DopaminergicStimulus(origin, stimulusId, rpe, target, action)`,
  `SerotonergicStimulus(origin, stimulusId, satiety)`, `NeuromodulatorTick(origin, stimulusId, circadianPhase)`,
  and the readout `NeuromodulatorState(origin, stimulusId, daTonic, serotoninTonic)`. All immutable.
- New `creature/components/NeuromodulatorSystem` **extends `CreatureComponent`** (untyped, batched
  `onReceive`, wired through `componentFactories`). Internal leaky-integrator state `dopamine`, `serotonin`
  (+ `lastPhasicDopamine`). Handlers: `DopaminergicStimulus`→`dopamine += rpe`;
  `SerotonergicStimulus`→`serotonin += satiety`; `NeuromodulatorTick`→`conc = conc*DECAY + baseline(phase)`.
  After each batch, publish `NeuromodulatorState` to `creature.fullAppraisal()`.
- New constants: `DOPAMINE_DECAY`, `SEROTONIN_DECAY`, baselines, `EXPECTANCY_LEVEL_BUCKETS`,
  `EXPECTANCY_ALPHA`, equilibrium band upper bound (reuse Mapa `[0.18, 2]`).
- Unit tests (via the `TestingHarness`/`ComponentActor` test pattern, or a direct `onReceive` drive):
  decay half-life, accumulation of repeated release stimuli, circadian baseline oscillation,
  satiety→serotonin monotonicity, `NeuromodulatorState` emitted after updates.

### Stage 3 — RPE loop in Valuation + decision-time context
- Extend `EvaluationStimulus` with an `ExpectancyContext` snapshot (dominant drive name+level at the
  interaction). Populate it in `HomeostaticRegulation` handlers (`handleNutritive`, `handleCholinergic`,
  `handleNociceptive`, `handleAnalgesic`, `handleTedium`) from `creature.emotions()`.
- Rewrite `Valuation.onReceive` per the architecture block: compute reward/expected/rpe, update
  predictor, **send `DopaminergicStimulus(rpe, target, action)` to `creature.neuromodulators()`**,
  call `varyProbability(target, action, |rpe|, rpe>0)`, gate memory with rpe.
- `Valuation` uses `creature.expectancy()` (TypedActor facade) and `creature.neuromodulators()`
  (`ComponentRef`, message-based) — both added to `Creature`.
- **Keep behaviour identical when `expectancyEnabled=false`**: fall back to the current
  `varyProbability(type, action, 1, valence)` + `reinforceWarmTraces(arousalVariation, ...)`.
- New persistence entity `creature/bd/ExpectancyState` (creature_key, cycle, drive, drive_level, target,
  action, expected, reward, rpe, da_tonic, serotonin_tonic, mode) following `EngramState` pattern.

### Stage 4 — Wire pools into the pacemaker (all via messages)
- `PartialAppraisal`: each cycle send `NeuromodulatorTick(circadianPhase)` and
  `SerotonergicStimulus(satiety)` to `creature.neuromodulators()`, where `satiety` = depth of active
  drives inside the equilibrium band. No synchronous reads; `EmotionalStimulus` is **not** modified
  (tonic levels reach `FullAppraisal` via the `NeuromodulatorState` message instead).

### Stage 5 — Behaviour modulation in FullAppraisal (behind `neuromodulationEnabled`)
- `FullAppraisal` handles `NeuromodulatorState` and caches `daTonic`/`serotoninTonic` in local fields
  (eventually-consistent; slow-varying tonic signal).
- Pass cached tonic levels into filters at selection time. `ActionProbabilityFilter`: softmax
  **temperature** = `f(daTonic)` (higher DA → flatter → explore) and scale `RandomFilter` weight.
  `TargetDistanceFilter`: patience/discount = `f(serotoninTonic)`; add mild rest/observe bias at high serotonin.
- All modulation no-ops (identity) when `neuromodulationEnabled=false`.

### Stage 6 — Settings, config, experiment, report
- `LearningSettings`: add `expectancyEnabled` (default false), `expectancyMode` (DISCRETE|CONTINUOUS),
  `neuromodulationEnabled` (default false); getters; parse in `Simulation.parseLearningSettings`.
- `CreatureActor.init()`: create `expectancy` and `neuromodulators` TypedActors (mode from settings);
  add `expectancy()` / `neuromodulators()` to `Creature` + `CreatureActor`.
- Experiment configs under `simulations/`: `exp_p57_baseline.conf` (expectancy off),
  `exp_p57_discrete.conf`, `exp_p57_continuous.conf` (+ `neuromodulationEnabled` on for the loop).
- `docker/docker-compose-exp-p57-*.yml` mirroring `docker-compose-exp-p55-fix.yml`.

---

## Key files

| Area | Files |
|---|---|
| New — expectancy | `creature/conditioning/expectancy/{ExpectancyPredictor,ExpectancyContext,DiscreteDriveExpectancy,ContinuousDriveExpectancy}.java` |
| New — neuromodulators | `creature/components/NeuromodulatorSystem.java` (untyped `CreatureComponent`); `stimuli/{DopaminergicStimulus,SerotonergicStimulus,NeuromodulatorTick,NeuromodulatorState}.java` |
| New — persistence | `creature/bd/ExpectancyState.java` |
| Reward loop | `creature/components/Valuation.java`, `creature/components/HomeostaticRegulation.java`, `stimuli/EvaluationStimulus.java` |
| Learning signal | `creature/conditioning/{OperantConditioning,OperantConditioningActor}.java` (accept graded delta) |
| Pacemaker | `creature/components/PartialAppraisal.java` (emits tick + serotonin) |
| Behaviour modulation | `creature/components/FullAppraisal.java`, `creature/actionSelector/{ActionProbabilityFilter,TargetDistanceFilter,RandomFilter}.java` |
| Wiring | `creature/Creature.java` (add `neuromodulators()`→`ComponentRef`, `expectancy()`→facade), `creature/CreatureActor.java` (register `NeuromodulatorSystem` in `componentFactories`; create `expectancy` TypedActor) |
| Settings/config | `cluster/settings/LearningSettings.java`, `cluster/settings/Simulation.java`, `common/Constants.java` |
| Repurpose | rewrite `docs/plans/issue-57-emotion-conditioned-action-selection.md`; update issue #57 + PR #62 |

---

## Design decisions (locked in discussion)
- **Full active loop** (not passive observers).
- **Repurpose #57** (supersede prior plan + PR #62).
- **Continuous variant = binned dominant-drive level.**
- Expectancy is a **dedicated symbolic predictor**, **not** JEPA (JEPA-critic swap is future work behind
  the same `ExpectancyPredictor` interface).
- **Neuromodulators are untyped, message-driven.** `NeuromodulatorSystem` is a `CreatureComponent`
  (not a TypedActor facade); DA/5-HT arrive **only** as explicit `DopaminergicStimulus` /
  `SerotonergicStimulus` messages; tonic levels are **published** via `NeuromodulatorState` (no
  synchronous cross-actor reads). Expectancy remains a TypedActor facade since it is pure computation,
  not a diffusing molecule.
- `actual` reward is **raw** (unscaled) so magnitude sensitivity is the measured difference between arms.

## Open sub-decisions (safe defaults chosen; flag on review)
- Serotonin phasic/aversive role: **tonic-only** for now.
- Number of level buckets, α, decay half-lives: start from constants above, tune if convergence poor.

---

## Verification

1. **Build:** `mvn package` clean (fat jar).
2. **Unit tests:** `mvn test` — new `ExpectancyPredictorTest`, `NeuromodulatorSystemTest`, updated
   `Valuation`/`OperantConditioning` tests; all existing tests green. Key assertions: DISCRETE vs
   CONTINUOUS prediction divergence on differing drive levels; graded `varyProbability` magnitude;
   pool decay/accumulation; `expectancyEnabled=false` reproduces current behaviour byte-for-byte.
3. **Smoke sim:** `cd docker && docker compose -f docker-compose-exp-p57-discrete.yml up`; confirm
   creatures live, `expectancy_state` rows populate, RPE spans both signs.
4. **Experiment (CLAUDE.md §5 protocol):** 3 arms (baseline / discrete / continuous), n=50 each.
   Extract via `python3 scripts/pg_extract.py`. Analyse: prediction MSE (expected vs reward),
   RPE convergence, mean lifetime, action-selection distribution vs Campos/2015 Fig 4.
   Report `docs/reports/issue-57-neuromodulatory-expectancy.md` (Purpose, Assumptions, Hypothesis,
   Results, Analysis + figures). Upload all data to HF `felipedreis/dl2l-experiments` under `p57/`.

**Hypotheses:** (H1) CONTINUOUS achieves lower prediction MSE than DISCRETE (captures magnitude);
(H2) both expectancy arms shift action-criteria frequencies toward Fig 4 without reducing mean lifetime;
(H3) tonic DA↑ increases exploration (action entropy) and 5-HT↑ increases rest/observe share.
