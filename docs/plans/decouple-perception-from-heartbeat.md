# Decouple the cognitive cycle from perception delivery (issue #85)

## Context

Issue #85 reports two measured defects in the creature cognitive loop, both artifacts of
issue #79 Phase B's "heartbeat + perception dual path" design:

1. **The cognitive cycle runs ~9x its nominal rate.** `CreatureActor.tick()` fires at
   `TARGET_CYCLE_HZ = 30`, but each tick does two independent things — an unconditional
   heartbeat `tell` to `PartialAppraisal`, *and* a positioning broadcast whose detector
   replies drive *additional* `PartialAppraisal.onReceive` calls. Measured 259–298 Hz
   across four conditions of the p84 campaign.
2. **Perception flickers.** 82.3% of cycles receive zero stimuli (heartbeat ticks);
   25.1% of consecutive cycle pairs cross the empty/non-empty boundary (~66 flips/s); a
   perception-bearing cycle is isolated 72% of the time. A physically stationary fruit is
   presented to the emotional/action-selection pipeline as appearing and vanishing within
   milliseconds, because `PartialAppraisal.buildEmotionalStimulus` substitutes a synthetic
   `Self` perception whenever the incoming batch is empty.

Intended outcome: exactly one cognitive cycle per wall-clock tick, each cycle carrying the
whole tick window's perceptual content, with creature liveness unchanged — plus a
before/after measurement quantifying the recalibration impact on non-dt-weighted constants.

### Why not the fix proposed in the issue

The issue proposes dropping the heartbeat and having `CollisionDetectorActor` always reply
(a background `LuminousStimulus` to `eyeRef` when the vision field is empty), with a ~500 ms
watchdog for liveness. **That would break sleep.** `FullAppraisal.produceCortical`
(`FullAppraisal.java:386-388`) sets `focus = 0.0` for SLEEP → `EffectorCortex` emits a
`FocusStimulus` → `Eye.onReceive` (`Eye.java:35`) drops *every* `LuminousStimulus` while
`visionFieldOpening < MIN_VISION_FIELD_OPENING`. A sleeping creature in empty space would
therefore see its only cycle driver disappear, falling back to the 2 Hz watchdog; with
`MIN_SLEEP_TICKS = 10` and `HOMEO_BATCH_SIZE = 20` cholinergic ticks per flush, sleep
recovery would slow by ~100x. The heartbeat is currently what keeps a sleeping creature
cognizing. The proposal also hands liveness to a cluster singleton that may live on another
node — the issue's own "Open concern" — and leaves the cycle rate detector-driven and
variable rather than pinned to 30 Hz.

**Chosen approach instead:** make the local tick the *sole* cycle driver and make perception
*state* rather than a trigger. `PartialAppraisal` buffers arriving `ProprioceptiveStimulus`
without cycling; the tick drains the buffer and runs exactly one cycle. Cycle rate becomes
exactly `TARGET_CYCLE_HZ`; the flicker disappears by construction (every cycle aggregates a
whole tick window); liveness stays on the local scheduler; `CollisionDetectorActor` and the
Eye sleep-gate are untouched.

---

## Step 0 — mandatory per CLAUDE.md

Copy this plan to `docs/plans/decouple-perception-from-heartbeat.md` and commit it before
implementing.

---

## Step 1 — Java: tick-gated cognitive cycle

### 1a. New message type

`src/main/java/br/cefetmg/lsi/l2l/stimuli/CognitiveTick.java` — `extends Stimulus`.

Extending `Stimulus` matters: `ComponentMessageQueue.dequeue()`
(`ComponentMessageQueue.java:112`) merges `Stimulus` instances into one batch, so the tick
coalesces with any stimuli queued alongside it. An unrecognised type would instead be
delivered as its own isolated single-element batch (`:140-155`), costing an extra
`onReceive` per tick.

Nothing reads `stimulusId`; construct as `new CognitiveTick(id, id.next())` from
`CreatureActor`. It is never added to `persistCycle`'s received-stimulus list.

### 1b. `CreatureActor.tick()` (`CreatureActor.java:270-273`)

Replace `componentRef(PartialAppraisal.class).tell("", ActorRef.noSender())` with a
`CognitiveTick` tell. `updatePositioningAttribute()` stays exactly as is — the detector
round-trip continues at one broadcast per tick, it just no longer *drives* cycles.

Note: the old `""` was silently discarded by `ComponentMessageQueue` (`:135-136`) — it only
ever served to force a mailbox run. The new message is real payload.

### 1c. `PartialAppraisal` (`PartialAppraisal.java:55-87`) — the core change

Add a field `private final List<ProprioceptiveStimulus> perceptBuffer = new ArrayList<>();`
and restructure `onReceive`:

```
partition the batch:
  ProprioceptiveStimulus -> perceptBuffer.add(ps)
  CognitiveTick          -> sawTick = true

if (!tickGated) { run cycle on this batch's own stimuli; return; }   // baseline arm
if (!sawTick)   { return; }                                          // buffer-only pass

run the existing cycle body with propStimuli = perceptBuffer
perceptBuffer.clear()
```

The cycle body (`checkDeath`, `tickMetabolicPacemaker`, `tickNeuromodulators`,
`releaseOrexin`, `tickEndocrine`, `buildEmotionalStimulus`, `persistCycle`) is unchanged —
only *when* it runs and *what list* it is handed. `dl2l_creature_cognitive_cycles_total`
stays incremented once per actual cycle, so it keeps meaning cycles, not deliveries.

Two ticks landing in one batch (a slow actor) run **one** cycle, not two — coalescing, not
replay. `tickMetabolicPacemaker`'s `cycleEquivalent` dt-weighting already accounts for the
longer elapsed interval, so no metabolic time is lost.

`buildEmotionalStimulus`'s `Self` fallback stays as-is. It now fires only when the whole
tick window genuinely yielded nothing — a real perceptual fact rather than an interleaving
artifact.

### 1d. Runtime switch for the A/B arms

Add `tickGatedCognition` to `LearningSettings` (default **true**), following the existing
boolean pattern exactly:

- `LearningSettings.java` — field, constructor param, `isTickGatedCognition()`.
- `Simulation.parseLearningSettings` (`Simulation.java:85-118`) — read as
  `!ls.hasPath("tickGatedCognition") || ls.getBoolean("tickGatedCognition")` (default-on,
  same shape as `circadianEnabled`).

This lets the baseline and fixed arms run from **one build**, so the new instrumentation
column (Step 2) exists in both — essential for a like-for-like before/after.

`PartialAppraisal` already receives `LearningSettings` via its constructor
(`CreatureActor.java:216`); no wiring needed.

---

## Step 2 — Java: make the flicker directly measurable

Today `behavioural_efficiency.numberofobjects` counts `perceptions.size()`, which includes
the synthetic `Self` — so an empty cycle and a one-real-object cycle both read `1`. The
issue had to reconstruct flip rate from raw `stimulus_state` Arrow dumps that survive in
only one trial. Fix that:

- `BehaviouralEfficiencyState` — add `int perceivedObjects` + getter/setter.
- `PartialAppraisal.persistCycle` — set it to `propStimuli.size()` (0 on the `Self`
  fallback). Leave `numberOfObjects` semantics untouched.
- `TableSchemas.java:213-218` — add
  `col("perceivedobjects", ColType.INT32, BehaviouralEfficiencyState::getPerceivedObjects)`.
- `scripts/dl2l_data/tables.py`, `behavioural_efficiency` query — add
  `bes.perceivedobjects AS n_perceived`.
- `TableSchemasTest` — extend the existing column-set assertion.

Flip rate then reduces to a one-line pandas expression over
`behavioural_efficiency.parquet`: `(df.n_perceived > 0).diff().abs().mean()`.

The Arrow read path (`scripts/dl2l_data/extract.py`) registers each `.arrow` file as a
DuckDB view, so the new column flows through with no DDL. The legacy psql path would need
the column in Postgres too — out of scope, the Arrow path is the one in use.

---

## Step 3 — close the olfactory extraction gap

`object_smelt_state` is written (`TableSchemas.java:184-190`, from `Nose.java`) but has no
entry in `scripts/dl2l_data/tables.py`, so smell-driven perception never reaches Parquet.
Python-only:

- New `smell_perceptions` entry joining `data.object_smelt_state` to
  `data.change_stimulus_state` for `creature_key`/`time` — mirror the existing
  `perceptions` query shape (which uses `css.key AS creature_key`, *not* the component-level
  `key` column that `seqCols` writes).
- Append to `TABLE_ORDER` (the `assert set(TABLE_ORDER) == set(TABLES)` at the file end
  enforces this).

Needed here because Step 5 has to account for *all* perception, not just vision, when
attributing residual flips.

---

## Step 4 — unit / component tests

**Existing tests that must be updated.** `TestingHarness.injectLuminous` currently ends with
`processUntilQuiescent()`, which drains `PartialAppraisal`'s dispatcher and therefore drives
a full cycle. Under tick-gating it will only buffer. Two changes:

- `TestingHarness.tick()` — send a `CognitiveTick` into `PartialAppraisal`'s dispatcher
  before draining, instead of just draining.
- Add an explicit `h.tick()` after `injectLuminous(...)` at the ~10 call sites that assert a
  decision: `TestingCreatureTest` (:82, :118, :217, :250), `FocusRegulationTest` (:51, :62,
  :86, :128), `ActionTendencyFunctionalTest` (:45), `OrexinFunctionalTest` (:112, :154),
  `NeuromodulationFunctionalTest` (:109), `BdSinkFunctionalTest` (:30).

This is mechanical and makes the tests more honest about the inject → tick → decide
sequence.

**New tests** (`PartialAppraisalTest` / a new functional test on `TestingHarness`):

1. A `ProprioceptiveStimulus` alone produces **no** `CorticalStimulus` — buffer only.
2. Injecting a luminous stimulus, then ticking, produces exactly **one** decision carrying
   that perception.
3. Injecting three luminous stimuli from distinct objects, then one tick, produces one
   decision with `perceptions.size() == 3` and `complexTask == true`.
4. Two ticks with an unchanged object in view produce two consecutive decisions both
   carrying the object — the direct flicker regression test.
5. Tick with an empty buffer still runs a full cycle (metabolism advances, `Self`
   perception, SLEEP/WANDER available) — the liveness guarantee.
6. `tickGatedCognition = false` restores per-delivery cycling — guards the baseline arm.

Also add a `ComponentMessageQueueTest` case asserting a `CognitiveTick` **merges** into a
`Stimulus` batch rather than being isolated as an unrecognised message — that property is
what keeps the tick to one `onReceive` per tick, and it silently depends on `CognitiveTick`
extending `Stimulus`.

---

## Step 4b — integration tests on the real actor system, running in CI

The whole class of defect in issue #85 is invisible to the existing suite: `TestingCreature`
is a single-threaded, no-`ActorSystem` fake with its own `BatchingDispatcher` mirroring
`ComponentMessageQueue`, and it has no real scheduler at all — `h.tick()` *is* the test
calling a method. Nothing today exercises the actual `clock` scheduler, the real
`ComponentMailbox`, `CollisionDetectorActor`'s round-trip, or the interaction between them,
which is precisely where both findings live. Add a real integration layer.

### 4b.1 — harness

`src/test/java/br/cefetmg/lsi/l2l/integration/SimulationIntegrationHarness.java`: boots a
**single-node, all-roles Akka cluster in one JVM**, wiring the same four actors `Main.java`
does (`Main.java:99-127`) minus the `GeometryWebService` (no port 8080 in CI):

```
akka.actor.provider          = akka.cluster.ClusterActorRefProvider   (unchanged)
akka.remote.netty.tcp.hostname = 127.0.0.1
akka.remote.netty.tcp.port   = 0        # ephemeral - no fixed-port collisions in CI
akka.cluster.seed-nodes      = []       # then Cluster.get(sys).join(selfAddress())
akka.cluster.min-nr-of-members = 1      # application.conf ships 2
akka.cluster.roles = [manager, idProvider, collisionDetector, holder]
```

Joining `selfAddress()` explicitly after startup is what makes `port = 0` workable — there
is no seed address to know in advance. `ComponentMessageQueueTest:172-176` is the existing
precedent for standing up a real `ActorSystem` inside a test.

Then: `PersistenceExtension.of(system).configure(tmpDir)` so all Arrow output lands in a
JUnit `@TempDir`, and `MetricsExtension.of(system)` for the counters. The metrics HTTP bind
is already non-fatal on failure (`MetricsExtension.java:139-142`), so a port clash between
concurrently-running test JVMs cannot fail the build; set `METRICS_PORT` via surefire
`<environmentVariables>` anyway to keep the log clean.

Helpers the harness exposes:
- `runFor(Duration)` — let the real scheduler drive the simulation for a bounded window.
- `flushAndReadTable(String)` — `Patterns.ask(bdActor, new Flush(), …)`, then read the
  table's `.arrow` file back. Extract `ArrowIpcBackendTest`'s private `readArrowFile` /
  `readValue` (`ArrowIpcBackendTest.java:41-60`) into a shared `ArrowTestReader` test util
  and have both use it.
- `counter(String name, String creatureKey)` — read
  `MetricsExtension.Impl.registry()` (already public, `MetricsExtension.java:97`).

Bounded-window runs, **not** run-to-death: a creature lives ~150 s by design
(`DELTA` is calibrated for it), which is far too slow for CI. One shared system booted in
`@BeforeAll` and a ~10 s window keeps the whole class to roughly 15 s.

Test resource `src/test/resources/simulations/integration_single_creature.conf` —
`holders = 1`, 1 creature, a handful of `RED_APPLE`s, `noUI = true`; modelled on
`simulations/baseline_1node_1creature.conf`.

### 4b.2 — the tests

`SimulationCycleRateIntegrationTest` — the ones that only a real actor system can prove:

1. **Cycle rate is the tick rate.** Run for a fixed window;
   `dl2l_creature_cognitive_cycles_total / elapsed` ≈ `TARGET_CYCLE_HZ` within a tolerance
   wide enough for CI-runner jitter (assert the *band*, e.g. 0.6–1.5x, not the point value).
   This is the direct regression test for Finding 1 and it fails loudly on today's code
   (~9x). Uses the `TARGET_CYCLE_HZ` env override that already exists
   (`Constants.java:133`) to run at a lower rate and shorten the window if needed.
2. **No flicker in real persisted data.** Read `behavioural_efficiency_state.arrow` back and
   assert the `(perceivedobjects > 0)` transition fraction is below a threshold (baseline
   ~25%) for a creature with a stationary fruit in view. This is Finding 2's acceptance
   criterion, asserted on the same column the experiment analysis will use — so the test and
   the report cannot drift apart.
3. **Detector replies do not add cycles.** Compare cycle counts between a run with many
   objects in sensory range and one in an empty world over the same window: under
   tick-gating they must match within jitter. Today they differ by ~an order of magnitude —
   this is the property the fix is really about, and it is unobservable without the real
   `CollisionDetectorActor`.
4. **Liveness with no perception.** Creature alone in an empty world still accrues cycles
   and its hunger drive rises — proves the local scheduler alone sustains cognition, the
   guarantee the issue's own proposal put at risk.
5. **Liveness when the detector is unreachable.** Stop `collisionDetector` mid-run; assert
   cycles keep accruing at the same rate. Directly closes issue #85's second acceptance
   criterion, and is the concrete argument for keeping the driver local.
6. **Baseline arm still behaves as before.** Same harness with
   `tickGatedCognition = false` reproduces the >100 Hz rate — guards the A/B switch that
   Step 5's `baseline` arm depends on, so a broken switch cannot silently invalidate the
   experiment.

`SimulationLifecycleIntegrationTest` — a broader smoke that the refactor does not break the
end-to-end path, worth having independently of this issue:

7. Boot → manager/holder handshake → world objects distributed → creature spawned →
   `Flush` → non-empty `creature_state`, `chosen_action_state`, `body_state`,
   `object_seen_state` Arrow tables, with a `chosen_action_state` row per cognitive cycle.
8. Creature eats: place a fruit at the creature's position, assert a
   `mouth_interactions_state` row and that the object is removed from the holder.

### 4b.3 — CI

`.github/workflows/ci.yml` runs `mvn test -Dtest='!ConsolidationPipelineTest'` on PRs, and
surefire's default includes already match `*IntegrationTest.java`, so no workflow change was
needed. `pom.xml` gained `akka-testkit` (test scope) and a surefire `METRICS_PORT` override.

---

## Step 4b — outcome (implemented 2026-08-06)

Three classes, nine tests, ~3 minutes total, all green; `mvn package` builds the fat jar.

| class | covers |
|---|---|
| `SimulationCycleRateIntegrationTest` | rate == tick rate in dense and empty worlds; rate independent of perception load; cognition survives the detector being stopped; baseline arm reproduces the pre-fix rate |
| `PerceptionFlickerIntegrationTest` | flips/second, gated vs baseline arm in the same world; `perceivedobjects` survives the write path |
| `SimulationLifecycleIntegrationTest` | handshake → spawn → full sensory-motor loop → persistence; one decision per cycle; hunger both accrues and is relieved |

**Measured, same world, same build:**

| | cycle rate | perception flicker |
|---|---|---|
| tick-gated | 30.5 Hz | 4.6 flips/s (15.2% of pairs) |
| baseline arm | 152.5 Hz | 44.1 flips/s (28.9% of pairs) |

**Bite check.** With `tickGatedCognition = false` forced in both test worlds, the gated
assertions fail as intended: 160 Hz against a nominal 30, and the flicker comparison
collapses. The tests are not vacuous.

### Findings from building this layer

1. **Flips-per-second, not fraction-of-pairs.** Issue #85's acceptance criterion is phrased
   as "the ~25% of consecutive pairs baseline", but that fraction is a per-cycle quantity —
   at 5x the cycle rate it counts 5x as many pairs, so fixing the rate moves its denominator
   as much as its numerator. Measured, the fraction only roughly halves (28.9% → 15.2%) while
   flips per second — what the downstream pipeline actually experiences — falls almost
   tenfold. **The issue's criterion should be re-worded to the per-second figure.**
2. **`MLServiceExtension` loaded eagerly regardless of need.** `Holder.preStart()` and
   `CreatureActor.init()` both resolved it unconditionally, so any run — including a config
   with no `WORLD_MODEL` filter — paid a DJL/PyTorch native-runtime download and hundreds of
   MB. Now gated on `CreatureActor.worldModelInUse()`. This is what makes the integration
   tests hermetic; it is also a straight win for every non-JEPA run.
3. **A startup race in the manager (not fixed here — worth its own issue).**
   `SimulationManager.handleRegister` starts the simulation as soon as the expected holder
   count is reached (after a fixed 5 s sleep), without ever waiting for the `idProvider`; if
   registration has not landed by then it asks a null ref for creature ids and the run dies
   with `question not sent to [null]`. `SequentialIdProvider.handleNewMember` compounds it by
   blocking its own dispatcher thread on a 5 s `Await`. Separate JVMs started seconds apart
   hide this in deployment; one JVM does not. The harness works around it by creating the
   holder last, after a settle.
4. **Two buffers sit between a cognitive cycle and a readable file** —
   `CreatureComponent.persist()` (256 states) and `ArrowIpcBackend` (4096 rows). A `Flush`
   moves neither's partial contents, so integration tests finalize the dump instead. Turning
   both off by environment variable was tried first and distorted the measurement badly: one
   record batch per row dropped a ~270 Hz simulation to ~1 Hz.

---

## Step 5 — mini-experiment (CLAUDE.md development-cycle step 5)

### Hypothesis and assumptions

- **H1.** Under tick-gating the measured cognitive-cycle rate equals `TARGET_CYCLE_HZ = 30`
  within scheduler jitter (baseline: 259–298 Hz).
- **H2.** The empty/non-empty flip rate drops from ~25% of consecutive pairs to
  the true rate at which objects enter and leave the sensory field — expected < 5%.
- **H3.** Mean lifespan is unchanged (`DELTA` is dt-weighted, so lifespan is
  cycle-rate-independent by construction). This is the control that confirms the
  dt-weighting works and isolates the rate change from a metabolic change.
- **H4 (recalibration).** Distance travelled per second and decisions per lifetime fall by
  roughly the measured 9x factor, because `FullAppraisal.produceCortical` has no time term
  and `Body.onReceive` applies full displacement per decision.
- *Assumption:* a single-creature world isolates the effect from inter-creature contention;
  object density is held identical across arms since density dominates perception rate.

### Arms (per the recalibration decision — three arms, answered with data)

| key | `tickGatedCognition` | constants |
|---|---|---|
| `baseline` | false | current `main` values |
| `tickgated` | true | current `main` values |
| `tickgated_recal` | true | Phase-A unwind applied |

**The unwind.** Issue #79 Phase A divided a set of rate constants by `S = 5.858`; Phase B
unwound only those it could dt-weight. Multiplying the remainder by `S` yields clean round
numbers, the same corroborating pattern `DELTA`'s comment in `Constants.java:8-19`
documents:

| constant | current | × 5.858 | round |
|---|---|---|---|
| `CHOLINERGIC_DELTA` | 1.70711e-2 | 1.00003e-1 | **1.0e-1** |
| `TEDIUM_IDLE_RATE` | 3.41422e-3 | 2.00005e-2 | **2.0e-2** |
| `TEDIUM_OBSERVE_RATE` | 8.53555e-3 | 5.00013e-2 | **5.0e-2** |
| `TEDIUM_WANDER_RELIEF` | 8.53555e-3 | 5.00013e-2 | **5.0e-2** |
| `PAIN_IMMUNE_RATE` | 8.53555e-4 | 5.00013e-3 | **5.0e-3** |
| `BOREDOM_RISE_RATE` | 1.36569e-4 | 7.99922e-4 | **8.0e-4** |

These accrue per event (`FullAppraisal.dispatchTediumStimulus`,
`HomeostaticRegulation.triggerImmuneResponseIfNeeded`, `NeuromodulatorSystem`,
`FullAppraisal.updateSleepState`), all of which fire once per cognitive cycle. **Once cycles
≡ ticks, "per-cycle" *is* dt-weighted** — so no new dt-weighting machinery is needed, only
the unwind. `MAX_STEP`/`MIN_STEP` need no change for the same reason: 30 Hz is the pre-#76
effective rate they were calibrated at, so tick-gating *restores* their intent rather than
breaking it. Per-cycle multiplicative decays (`OREXIN_DECAY`, `DOPAMINE_DECAY`,
`SEROTONIN_DECAY`, `CORTISOL_DECAY`) are left alone — their comments already reason in
ticks, and that reading becomes exactly true.

### Mechanics

- `experiments/p85_cycle_rate_and_flicker.yml`, upload prefix `p85/` (never disabled).
- Three `simulations/p85_*.conf`, single creature, identical world across arms, differing
  only in `learningSettings.tickGatedCognition`. Base them on
  `simulations/20260728_tick_rate_diagnostic_1_baseline.conf` — the existing tick-rate
  diagnostic config.
- Trial count: start from 10/arm and re-derive from the pilot's lifespan variance (Mann-
  Whitney power at α = 0.05); H1/H2 are near-deterministic and need few trials, H3/H4 carry
  the variance.
- Validate the spec: `python3 scripts/validate_experiment.py experiments/p85_cycle_rate_and_flicker.yml`.
- Run: `cd ansible && ansible-playbook -i inventories/local run-experiment.yml -e experiment=p85_cycle_rate_and_flicker -e analyze=true`.

### Analysis and report

`analysis/experiments/p85_cycle_rate_and_flicker.py` with a `run(cfg)` entry point, built on
`dl2l_analysis` (`config.ExperimentAnalysis.from_spec`, `loading.load_all`,
`stats.cond_stats`/`kruskal_test`, `figures`, `report`) — follow
`analysis/experiments/rotten_fruit_v1.py`.

Figures:
1. Cycle rate per arm (cycles / lifespan, from `behavioural_efficiency` row counts and
   `creatures.lifetime_s`), with a 30 Hz reference line.
2. Flip rate per arm — `(n_perceived > 0)` transition fraction, with the 25.1% baseline
   reference.
3. Run-length distribution of perception-bearing cycles (baseline median 1 → expect a much
   longer tail).
4. Lifespan and distance-per-second per arm (H3/H4), from `creatures` and `body_states`.
5. Sleep/tedium/pain trajectories, `tickgated` vs `tickgated_recal` — the evidence AC #4 is
   decided on.

Report: `docs/reports/p85_perception_flickering_report.md`, sections Purpose / Assumptions /
Hypothesis / Results / Analysis, all figures embedded. It must state explicitly that the p84
campaign (`p84/` on HF) remains internally valid — the miscalibration applied uniformly to
every arm — but that its absolute movement and interaction scales do not mean what the
constants intended.

---

## Acceptance criteria mapping

| issue #85 AC | closed by |
|---|---|
| Flip rate drops substantially from ~25% | Step 1c; asserted in CI by integration test 2; measured in Step 5 fig. 2 |
| Liveness preserved when detector is slow/unreachable | Step 1b keeps the local scheduler as the sole driver; integration tests 4 and 5 (detector stopped mid-run) |
| Before/after cycle rate, flip rate, lifetime measured | Step 5 arms `baseline` vs `tickgated` |
| Decision recorded on dt-weighting vs recalibration | Step 5 arm `tickgated_recal` + report |
| (incidental) olfactory perception never extracted | Step 3 |

---

## Verification

1. `mvn package` — clean compile, fat jar builds.
2. `mvn test` — green, including the six new component tests and the eight integration
   tests. Sanity-check the integration tests actually bite: run tests 1–3 against a build
   with `tickGatedCognition` forced off and confirm they **fail** (~9x rate, ~25% flip
   rate). A green integration test that would also pass on the buggy code is worthless.
3. `mvn test -Dtest='!ConsolidationPipelineTest'` — the exact CI command, to confirm the new
   tests run and pass in the pipeline's configuration.
4. Local smoke run with the UI on `:8080`
   (`ansible-playbook -i inventories/local run-experiment.yml -e experiment=smoke`) —
   confirm creatures move, sleep, eat and die, and that the run terminates normally.
5. Inspect one trial's `behavioural_efficiency.parquet`: `n_perceived` present; cycles /
   lifetime ≈ 30 in the `tickgated` arm and ≈ 260+ in `baseline`.
6. Confirm `smell_perceptions.parquet` is non-empty for a world with objects in olfactory
   range.
7. Full three-arm run + `-e analyze=true`; data lands under `p85/` on
   `felipedreis/dl2l-experiments`.

## Contingency

If Step 5 shows residual flicker attributable to sweep/tick beat jitter (a tick window
occasionally catching zero or two detector sweeps), add a short percept hold — retain the
last non-empty buffer for `PERCEPT_HOLD_CYCLES` cycles, biologically motivated as visible
persistence / iconic memory. Deliberately **not** in the initial implementation: the
detector sweep is triggered by the creature's own tick, so sweeps should arrive one per tick
window in steady state, and an unnecessary hold would let a just-eaten object linger as
perceived.

## Branch

All work on `claude/perception-flickering-issue-j8dfjz`; push with
`git push -u origin claude/perception-flickering-issue-j8dfjz`. No PR unless asked.
