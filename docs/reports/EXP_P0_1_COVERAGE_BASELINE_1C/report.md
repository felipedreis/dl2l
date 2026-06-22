# EXP-P0-1 — Perception Coverage Probe (Baseline, 1 Creature)

**Phase:** 0 — De-risk before building  
**Task:** 0.1 — Coverage probe ([Issue #10](https://github.com/felipedreis/dl2l/issues/10))  
**Date:** 2026-06-22  
**Simulation config:** `simulations/baseline_1node_1creature.conf`  
**Extractor:** `PerceptionCoverageExtractor` → `perceptionCoverage.csv`  
**Analysis script:** `analysis/coverage_probe.py`

---

## 1. Purpose

Before training a JEPA world model on creature trajectories, we need to know whether
the state distribution visited by the reactive (Mode-1) policy is broad enough to
support generalisation. A model trained on a narrow slice of the state space will
confidently mispredict anything outside it. This experiment characterises the distribution
along every dimension of the `Perception` vector — the input the world model will receive
at every decision step.

---

## 2. Assumptions

1. **`ObjectSeenState` is the ground truth for what the creature perceives.** Every object
   the `Eye` reports visible is recorded in `data.object_seen_state`. We treat this table
   as the complete perception log.

2. **One sample = one visible object at one decision step.** If three objects were visible
   during a single `onReceive` pass, three rows appear. We do not collapse to per-step
   summaries.

3. **`distance` and `angle` are the continuous perception inputs; `objectType` is categorical.**
   These are the three dimensions of the `Perception` record
   (`Perception.distance`, `Perception.angle`, `Perception.objectType`). The fourth field
   `direction` (creature heading) is stored in `ObjectSeenState` but was not included in
   this probe — see Section 7.

4. **This simulation config has two object types.** `baseline_1node_1creature.conf`
   configures 90 `RED_APPLE` and 90 `GREEN_APPLE` objects — **no `GRAY_APPLE`**.
   The absence of `GRAY_APPLE` in the data is therefore a property of the simulation
   config, not a coverage failure of the policy.

5. **The database was clean at the start of this run.** The simulation was run via
   `docker-compose up` immediately after `docker-compose down`, which removes the
   PostgreSQL container and its ephemeral storage (no named volume is configured).
   EclipseLink also runs `drop-and-create-tables` at holder startup. The perception
   timestamps in the raw CSV (epoch ms 1,782,129,501,898 → 1,782,129,594,044, span ≈
   92,146 ms) confirm the data is from a single run conducted on 2026-06-22.

   > **Known risk:** if `docker-compose down` is not run between trials, the extractor
   > accumulates data from all past runs in the same DB, producing a mixed dataset.
   > See Section 7 for a proposed mitigation.

---

## 3. Hypothesis

The Mode-1 reactive policy is a pure forager: it approaches the nearest edible object
and eats it, otherwise wanders. We expect:

- **Distance:** the creature sees objects across the full visual range, with a concentration
  around medium distances because objects close to the creature are consumed quickly
  (reducing near counts) and objects at maximum range are transient. We expect a roughly
  bell-shaped distribution, not uniform.
- **Angle:** the creature sees objects in all directions. A small front-sector concentration
  is plausible (APPROACH brings frontal objects into the near zone faster). A structurally
  dead sector (large contiguous gap) would be a risk for training.
- **Object type:** both types should appear in proportion to their world density (1:1 here).
  Any large imbalance would mean the model trains on too few examples of one type.
- **PCA intrinsic dimensionality:** one-hot encoding two categories produces a linearly
  dependent fourth feature; we expect 3 effective components covering 100% of variance,
  with each component carrying roughly equal weight if all three independent dimensions
  are informative.

---

## 4. Experiment

### 4.1 Setup

| Parameter | Value |
|-----------|-------|
| Simulation config | `baseline_1node_1creature.conf` |
| Creatures | 1 |
| World objects | 90 × RED_APPLE, 90 × GREEN_APPLE (180 total) |
| Creature lifetime | **92.2 s** (~1.54 simulation minutes) |
| Total distance traveled | 164,583 world units |
| Nutrients eaten | 143 |
| Total perception events logged | 31,836 |
| Action selections (at end of life) | 136 AFFORDANCE, 123 RANDOM, 0 MEMORY |

> **Time-unit note.** `lifetimes.csv` is produced by `LifetimesExtractor` which applies
> `MILLIS_TO_SECONDS (1e-3)`, so the value 92.205 is in **seconds**. The arousal history
> uses `MILLIS_TO_MINUTES`, so its time axis ends at 1.537 **minutes** = 92.2 s — both
> consistent. The simulation ran and completed in approximately 92 seconds of wall-clock
> time.

The simulation was run via Docker Compose. After the holder container exited (code 0),
the data extractor was run from within the same Docker network using the freshly-built
jar mounted over the image jar:

```bash
# start fresh (removes DB container + data)
cd docker && docker-compose down && docker-compose up -d

# wait for holder to exit, then extract
docker run --rm --network docker_dl2l-network --entrypoint java \
  -v <new-jar>:/dl2l/run/dl2l.jar -v /output:/output dl2l \
  -Dconfig.file=/config/docker-config.conf -jar dl2l.jar \
  --host localhost --port 2551 --roles holder --extractor --save /output
```

### 4.2 Analysis

`analysis/coverage_probe.py` was run with:
- `wd` pointing at the extractor output directory
- `KNOWN_TYPES = {"RED_APPLE", "GREEN_APPLE", "GRAY_APPLE"}` (all three apple types, to
  catch types absent from this config)
- `COVERAGE_THRESHOLD = 0.01` (1%)

Steps: load and concat CSVs → per-dim descriptive stats → objectType counts with
absent-type flagging → one-hot encode + standardise + PCA → histogram and scree plots.

---

## 5. Results

### 5.1 Creature summary

The creature survived **92.2 seconds**, traveled 164,583 world units, and ate 143
nutrients. Final hunger arousal reached 7.0 (the observed maximum — the creature died
hungry), while sleep arousal stayed low at 0.19 (sleep need was not the binding
constraint). Action selection was split between AFFORDANCE (136 choices, reactive) and
RANDOM (123 choices), with **zero MEMORY or SHORT_TERM_MEMORY selections** — confirming
that the memory system is effectively dead in the current codebase, as documented in the
HLD. This observation is incidental to the coverage probe but serves as a sanity check.

### 5.2 Distance distribution

| Statistic | Value (world units) |
|-----------|---------------------|
| Min | 1.50 |
| Max | 949.85 |
| Mean | 381.75 |
| Std | 173.37 |
| p5 | 97.48 |
| p50 | 377.34 |
| p95 | 681.73 |

![Distance histogram](distance_hist.png)

The distribution is **bell-shaped and continuous**, covering the full eye range from
near-contact (~1.5 units) to the sensor maximum (~950 units). The peak around 300–450
units reflects the balance between consumption dynamics (objects close to the creature
are eaten quickly and disappear, reducing near-range counts) and sensory geometry
(objects at maximum range spend fewer ticks inside the field of view). The p5–p95
span (97 → 682 units) is wide with no dead zones. This range is adequate for training.

### 5.3 Angle distribution

| Statistic | Value (radians) |
|-----------|----------------|
| Min | −3.14 |
| Max | +3.14 |
| Mean | +0.21 |
| Std | 1.74 |
| p5 | −2.85 |
| p95 | +2.82 |

![Angle histogram](angle_hist.png)

The distribution **covers the full [−π, +π] range** with no completely absent sector.
However, there is a notable trough around [−2.0, −1.0] rad (the left-rear quadrant,
roughly 115°–57° behind the creature's left shoulder): counts in this region are 30–50%
lower than the front-facing bins. Two plausible causes:

- The APPROACH action moves the creature toward visible objects, rapidly shifting
  left-rear objects into a frontal position and reducing dwell time in that angular bin.
- A possible right-turn bias in the WANDER/TURN action selection.

The gap is a **reduced-density region, not an absent one**. The world model will see
fewer training examples for left-rear angles; this should be monitored but does not by
itself require exploratory episodes.

### 5.4 Object-type breakdown

| Type | Count | Fraction | Note |
|------|-------|----------|------|
| GREEN_APPLE | 18,071 | 56.8% | |
| RED_APPLE | 13,765 | 43.2% | |
| GRAY_APPLE | 0 | 0.0% | **absent from config** |

Both types present in the simulation are observed. The 57/43 split is a mild imbalance
despite equal world density (90 objects each). The difference arises because random
initial placement placed GREEN_APPLE objects closer to the creature's starting position
in this run, making them the more frequent nearest target. Over a multi-creature
population run this imbalance is expected to average out.

`GRAY_APPLE` does not appear because it is **not defined** in
`baseline_1node_1creature.conf`. This is a configuration constraint, not a policy
coverage failure. If the production training simulation (`basic.conf`, which configures
100,000 of each of the three apple types) is used for data collection, a separate probe
on that config must be run before drawing any coverage conclusions for `GRAY_APPLE`.

### 5.5 PCA — intrinsic dimensionality

| Component | Explained variance | Cumulative |
|-----------|--------------------|------------|
| PC1 | 50.12% | 50.12% |
| PC2 | 26.00% | 76.11% |
| PC3 | 23.89% | 100.00% |
| PC4 | 0.00% | 100.00% |

![PCA scree plot](pca_scree.png)

**Three components explain 100% of variance; PC4 is identically zero.** This is expected:
one-hot encoding two categories (RED, GREEN) produces a fourth feature that is the
linear complement of the third (`type_RED = 1 − type_GREEN`), contributing zero
independent information. The effective state space is 3-dimensional.

The three components carry roughly balanced weight (50% / 26% / 24%), meaning distance,
angle, and object type all contribute meaningfully. None is a noise column. This is a
positive signal: the JEPA encoder has clear, non-redundant structure to capture in each
independent dimension.

---

## 6. Conclusions

### 6.1 Is the Mode-1 distribution adequate for species model training?

**Partially yes, with one important qualification.**

For the two types present in `baseline_1node_1creature.conf`:

| Dimension | Coverage verdict |
|-----------|-----------------|
| Distance | ✅ Full range, well-spread bell distribution |
| Angle | ✅ Full range; mild left-rear under-density, not a hard gap |
| Object type (RED, GREEN) | ✅ Both observed; mild 57/43 imbalance, acceptable |
| Effective dimensionality | ✅ 3 independent components, all informative |

**The qualification:** this experiment covers only a 2-type simulation. If training data
is drawn from a 3-type config (e.g., `basic.conf`), `GRAY_APPLE` will be absent and the
world model will have no training signal for that type. A separate probe on that config
is required.

### 6.2 Decision on exploratory episodes (Task 2.1)

> **For `baseline_1node_1creature.conf` training data: no random-policy episodes
> required.** The reactive policy visits the full distance and angle range, and both
> object types present in this config appear adequately.
>
> **If training data is drawn from a 3-type config (`basic.conf`): run a separate probe
> first.** `GRAY_APPLE` coverage cannot be assumed.
>
> **The left-rear angle under-density is noted as a known limitation.** Monitor whether
> the trained model shows systematically higher prediction error for objects approached
> from that quadrant; if so, targeted random-policy episodes covering that sector are
> the corrective action.

---

## 7. Limitations and future work

- **Single creature, 92-second run.** Population-level coverage (multiple creatures,
  varied random seeds, longer runs) would give a more reliable distribution estimate.
  This probe is a first-pass check, not a definitive characterisation.

- **`direction` not included.** The creature's heading (`ObjectSeenState.direction`) is a
  fourth continuous dimension of the full perceptual state. It was excluded here; a
  follow-up probe should include it if the world model takes heading as an input.

- **DB cleanliness depends on manual `docker-compose down` between trials.** There is no
  mechanism today to separate runs inside the same database. If `down` is skipped, the
  extractor silently accumulates data from all past runs. Two mitigations are worth
  considering:
  - **Trial number column:** add a `trial_id` column (sequence or UUID) written at
    simulation startup; the extractor filters by it.
  - **Per-trial PostgreSQL schema:** create a new schema (e.g., `data_trial_42`) per run
    and set `eclipselink.target-schema` from the simulation config; the extractor
    targets the latest schema by name convention.
  The schema approach avoids any JOIN complexity and keeps historical data intact.

- **`KNOWN_TYPES` must be set per config.** The script's `KNOWN_TYPES` constant is
  currently hardcoded to all three apple types. For a `baseline_*` run it should be
  `{"RED_APPLE", "GREEN_APPLE"}` to avoid a misleading `GRAY_APPLE ← ABSENT` warning.
