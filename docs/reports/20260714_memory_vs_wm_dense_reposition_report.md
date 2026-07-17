# Experiment Report: Memory vs. JEPA World Model — Dense World + Reposition

**Experiment ID:** `20260714_memory_vs_wm_dense_reposition`
**Date:** 2026-07-14 / 2026-07-16
**Trials:** 5 trials × 4 conditions × 10 creatures = **200 creatures analyzed**
**Analysis script:** `analysis/experiments/20260714_memory_vs_wm_dense_reposition.py`
**Data:** `ml/data_20260714_memory_vs_wm_dense_reposition/`

---

## Purpose

Re-run the `20260709_memory_vs_wm_v1` comparison (episodic memory filter vs. JEPA world-model
filter, with/without consolidation) under a **denser, self-replenishing world** to test whether
the earlier experiment's findings — a strong JEPA survival advantage, a memory-filter survival
*penalty*, and dramatic Tedium suppression under JEPA — depend on resource scarcity. This
variant doubles the world size and creature count and turns on food `reposition` (eaten apples
regrow instead of permanently depleting the world), removing the scarcity pressure that made
foraging strategy consequential in the original experiment.

This report covers four of the five planned conditions (`1_baseline`, `2_memory_only`,
`3_memory_consolidation`, `4_jepa_rpe_only`). `5_jepa_rpe_consolidation` is excluded — its data
showed a behavioral anomaly under investigation (see `docs/plans/ccad-singularity-experiments.md`)
and is being re-collected separately.

---

## Assumptions

- World layout: 1200×900 (2× the original `20260709_memory_vs_wm_v1`'s world), 10 creatures per
  trial (2×), 500 RED_APPLE + 500 GREEN_APPLE + 500 GRAY_APPLE, 50 CACTUS, 100 ALOE.
- `reposition = true`: eaten food objects respawn, so the world never runs out of food — the key
  manipulation relative to the original experiment (`reposition = false`).
- `maxRuntimeMinutes = 60` for every condition.
- The `unified_critic` JEPA model represents the species prior for the WORLD_MODEL filter and the
  JEPA RPE baseline, same as in `20260709_memory_vs_wm_v1`.
- All four conditions share the same world layout and creature count (n = 50 per condition: 5
  trials × 10 creatures).

---

## Conditions

Each condition adds exactly one mechanism on top of the previous one, isolating its effect:

| # | Key | What changes vs. the previous row | Filters | Consolidation | Expectancy |
|---|-----|------------------------------------|---------|:--------------:|:----------:|
| 1 | `1_baseline` | — (starting point) | TARGET_DISTANCE, AFFORDANCE, RANDOM | off | DISCRETE |
| 2 | `2_memory_only` | **+ episodic memory filter** (MEMORY replaces most RANDOM fallback choices) | TARGET_DISTANCE, AFFORDANCE, MEMORY, RANDOM | off | DISCRETE |
| 3 | `3_memory_consolidation` | **+ sleep consolidation** (`MemoryTraceConsolidator` strengthens/prunes engrams during sleep) | TARGET_DISTANCE, AFFORDANCE, MEMORY, RANDOM | **on** | DISCRETE |
| 4 | `4_jepa_rpe_only` | **MEMORY → WORLD_MODEL**, and dopamine now fires on JEPA world-model prediction error instead of the tabular running mean; consolidation off again | TARGET_DISTANCE, AFFORDANCE, WORLD_MODEL, RANDOM | off | **JEPA** |

All conditions keep the full subsystem stack on: orexin, endocrine, neuromodulation,
expectancy, action tendency, circadian rhythm.

- **1 → 2** isolates the effect of giving creatures episodic memory as a fourth action-selection
  filter (previously encountered good/bad locations bias future choices, replacing most RANDOM
  fallback).
- **2 → 3** isolates the effect of consolidating those memories during sleep (strengthening
  high-eligibility engrams, pruning weak ones) versus leaving them as raw, unconsolidated traces.
- **3 → 4** is not incremental on 1-3 — it swaps the whole strategy: MEMORY (symbolic, engram-based)
  is replaced by WORLD_MODEL (a learned JEPA predictor), and the dopamine/RPE signal driving
  learning switches from a tabular DISCRETE running-mean to the JEPA model's own prediction error.
  Consolidation is off, so condition 4 measures the JEPA filter and JEPA RPE signal in isolation,
  the same way condition 2 measures MEMORY in isolation.

---

## Hypothesis

| # | Hypothesis |
|---|-----------|
| H1 | Under resource abundance (reposition=true), the JEPA survival advantage seen in the scarce-world experiment shrinks or disappears |
| H2 | The episodic memory filter's survival penalty (seen in the scarce-world experiment) also shrinks or disappears under abundance |
| H3 | Memory consolidation improves on memory-only performance |
| H4 | The JEPA RPE signal remains qualitatively larger than the DISCRETE baseline's, independent of world scarcity |
| H5 | Tedium suppression under JEPA (a striking effect in the scarce world) is reduced when foraging pressure is removed |

---

## Results

### 1. Survival — Wall-clock Seconds

![Lifespan](figures/20260714_memory_vs_wm_dense_reposition/01_lifespan.png)

| Condition | Mean (s) | ± SD | n |
|-----------|:--------:|:----:|:-:|
| Baseline | 3311.65 | 52.56 | 3\* |
| Memory | 3202.41 | 267.23 | 12\* |
| Mem+Consol | 3213.66 | 260.72 | 16\* |
| JEPA | 3295.73 | 162.60 | 16\* |

\*n here counts creatures that died before the 60-minute cap; most creatures in every condition
survived the full run and are censored at 3600s, so this n is small and the "lifetime" figures
above are dominated by the simulation's own time cap, not by creature death. Interpret with
caution (see Analysis).

Kruskal-Wallis: H = 0.715, p = 0.8698 — **no significant differences** between any pair of
conditions.

### 2. Survival — Decision Ticks (inference-independent)

| Condition | Mean ticks | ± SD | n |
|-----------|:----------:|:----:|:-:|
| Baseline | **5976** | 482 | 50 |
| Mem+Consol | 5887 | 815 | 50 |
| Memory | 5629 | 505 | 50 |
| JEPA | 5555 | 1268 | 50 |

Kruskal-Wallis: H = 19.444, p = 0.0002.

| Comparison | p-value | Significance |
|------------|:-------:|:------------:|
| Baseline vs Memory | 0.0006 | *** |
| Baseline vs Mem+Consol | 0.1637 | ns |
| Baseline vs JEPA | < 0.0001 | *** |
| Mem+Consol vs JEPA | 0.0225 | * |

Baseline runs significantly *more* decision cycles than both Memory and JEPA in the same
wall-clock window — consistent with baseline's simpler filters requiring less per-tick
computation (no memory lookup, no JEPA inference).

> **H1: Not confirmed as a "shrinks" effect — the JEPA advantage is fully gone.** Unlike the
> scarce-world experiment (JEPA 720s raw / 441s corrected vs. 290s baseline, p < 0.001), here
> JEPA's raw lifetime (3296s) is statistically indistinguishable from baseline (3312s, p = 0.96).
> With food no longer scarce, JEPA's foraging-quality advantage has no opportunity to matter for
> survival — nearly every creature in every condition survives to the time cap regardless of
> strategy.

> **H2: Confirmed.** Memory's survival penalty from the scarce-world experiment (237s vs. 290s
> baseline, p = 0.0009) is gone (3202s vs. 3312s baseline, p = 0.63 ns).

### 3. Drive Regulation (Arousal)

![Arousal over time](figures/20260714_memory_vs_wm_dense_reposition/02_arousal_time.png)

| Condition | Mean Arousal | ± SD |
|-----------|:-----------:|:----:|
| Baseline | 13.15 | 4.30 |
| Memory | 13.31 | 4.62 |
| Mem+Consol | 13.48 | 4.60 |
| JEPA | 13.23 | 4.39 |

All four conditions cluster tightly (13.15–13.48) — no meaningful separation.

### 4. Per-Drive Trajectories

![Per-drive trajectories](figures/20260714_memory_vs_wm_dense_reposition/03_per_drive.png)

| Drive | Baseline | Memory | Mem+Consol | JEPA |
|-------|:--------:|:------:|:----------:|:--------:|
| Hunger | 3.63 | 3.62 | 3.71 | 3.62 |
| Sleep | 2.76 | 2.82 | 2.84 | 2.82 |
| Pain | 5.55 | 5.47 | 5.55 | 5.55 |
| **Tedium** | 1.22 | 1.40 | 1.38 | **1.23** |

> **H5: Confirmed.** In the scarce world, JEPA suppressed Tedium by 66–70% relative to baseline
> (0.74 vs. 2.43). Here, JEPA's Tedium (1.23) is essentially identical to baseline's (1.22) — the
> suppression effect is gone. With food abundant and reachable everywhere, baseline creatures no
> longer accumulate the "nothing interesting is happening" signal that Tedium tracks, so JEPA's
> novelty-driven dopamine has nothing left to suppress.

### 5. Action Selection

![Filter distribution](figures/20260714_memory_vs_wm_dense_reposition/04_action_filters.png)

| Condition | ACTION_TENDENCY | AFFORDANCE | MEMORY | WORLD_MODEL | RANDOM |
|-----------|:--------------:|:---------:|:------:|:-----------:|:------:|
| Baseline | 35.1% | 18.3% | — | — | 46.3% |
| Memory | 35.2% | 18.6% | 35.6% | — | 10.4% |
| Mem+Consol | 35.6% | 18.8% | 34.7% | — | 10.6% |
| JEPA | 36.4% | 18.2% | — | **35.2%** | 10.0% |

WORLD_MODEL fires at 35.2% of JEPA-condition cycles — higher than the 25% seen in the scarce
world, likely because a denser world gives the filter more nearby candidate objects to evaluate
per cycle.

### 6. Behavioural Efficiency

![Efficiency](figures/20260714_memory_vs_wm_dense_reposition/05_efficiency.png)

Mean efficiency is nearly identical across all four conditions (0.63–0.64) — as in the scarce
world, filter choice does not change per-action efficiency.

### 7. Eating Behaviour & Cactus Avoidance

![Eating behaviour and cactus avoidance](figures/20260714_memory_vs_wm_dense_reposition/06_eating_behaviour.png)

| Condition | Gray Apple | Green Apple | Red Apple | Total EAT | Cactus avoidance |
|-----------|:----------:|:-----------:|:---------:|:---------:|:-----------------:|
| Baseline | 271 (31%) | 346 (40%) | 256 (29%) | 873 | 53.0% |
| Memory | 202 (30%) | 299 (44%) | 172 (26%) | 673 | 53.9% |
| Mem+Consol | 261 (33%) | 291 (37%) | 243 (31%) | 795 | 52.0% |
| JEPA | 203 (31%) | 264 (41%) | 178 (28%) | 645 | 51.0% |

Food-quality selection and cactus avoidance are all within a few points of each other — no
condition shows the clear selectivity advantage JEPA+Consol showed in the scarce world (45%
Green Apple vs. 40% baseline there). Hunger at time of eating is also similar across conditions
(3.89–4.20).

### 8. Neuromodulators

![Neuromodulators](figures/20260714_memory_vs_wm_dense_reposition/08_neuromodulators.png)

Tonic neuromodulator levels are visually similar across all four conditions, as in the scarce
world.

### 9. Expectancy / RPE

![RPE](figures/20260714_memory_vs_wm_dense_reposition/09_expectancy_rpe.png)

| Condition | \|RPE\| mean | SD |
|-----------|:-----------:|:--:|
| Baseline | 0.0889 | 0.247 |
| Memory | 0.0753 | 0.227 |
| Mem+Consol | 0.0702 | 0.208 |
| **JEPA** | **0.3867** | 1.916 |

JEPA's RPE is still clearly larger than the DISCRETE conditions', but the ratio has compressed
sharply — about **4.3×** baseline here vs. **15×** in the scarce world. Baseline's own RPE is
also roughly double what it was in the scarce world (0.089 vs. 0.044), suggesting a denser,
faster-changing world raises prediction error for the tabular baseline too, narrowing the gap.

> **H4: Confirmed, but the effect is much smaller under abundance.** The direction holds (JEPA
> RPE > DISCRETE RPE) but the magnitude drops from a 15× ratio to 4.3×.

### 10. Memory Engrams

![Engrams](figures/20260714_memory_vs_wm_dense_reposition/10_engrams.png)

| Condition | Engrams | Mean Elig. | Mean \|delta\| |
|-----------|--------:|:----------:|:--------------:|
| Baseline | 106,158 | 0.225 | 0.0200 |
| Memory | 91,676 | 0.225 | 0.0170 |
| Mem+Consol | 100,420 | 0.225 | 0.0158 |
| **JEPA** | 90,220 | 0.225 | **0.0869** |

Same pattern as RPE: JEPA's engram update magnitude is elevated (4.3× baseline) but far below
the 14× seen in the scarce world.

**Why does baseline form the most engrams, with no `MEMORY` filter enabled?** Engram formation
is entirely independent of `enabledFilters`. `FullAppraisal.updateMemory()` pushes a short-term
memory trace into `MemorySystem` on *every* cognitive cycle, unconditionally; `Valuation` then
mints and persists an `Engram` per still-eligible trace on every evaluation event — neither step
checks which action-selection filters are active. `enabledFilters`/`MEMORY` only controls whether
`MemoryFilter` *reads* those engrams back to bias action choice — it is a consumer, not a
producer. So every condition writes engrams at essentially the same rate regardless of whether
MEMORY is enabled; baseline's slightly higher count here simply reflects it completing more
decision ticks in the same wall-clock window (Section 2), giving more evaluation events for
`Valuation` to fire on — not any memory-related setting.

### 11. Sleep Episodes

![Sleep](figures/20260714_memory_vs_wm_dense_reposition/11_sleep_episodes.png)

| Condition | Episodes (total) | Mean duration (ticks) | SD |
|-----------|:---------------:|:--------------------:|:--:|
| **Baseline** | **105** | 13.93 | 5.81 |
| Mem+Consol | 70 | 13.80 | 5.92 |
| Memory | 66 | 14.82 | 6.40 |
| JEPA | 59 | 13.34 | 4.13 |

This inverts the scarce-world pattern, where JEPA had the *most* sleep episodes (415, driven by
its much longer raw lifetime). Here all four conditions run to nearly the same wall-clock time
cap, and baseline — with the fewest per-tick computations and the most decision ticks (Section
2) — accumulates the most sleep-eligible cycles.

### 12. JEPA Inference Latency

| Condition | Count | Mean (ms) | Median (ms) | Max (ms) |
|-----------|------:|:---------:|:-----------:|:--------:|
| JEPA | 97,636 | 6.39 | 5 | 524 |

Mean inference latency (6.4ms) is much lower than the scarce-world experiment's (~48ms) — a
faster JEPA model/hardware path, not a world-density effect, and consistent with this
experiment's much smaller measured WORLD_MODEL overhead (12.5s total vs. 228–279s in the scarce
world).

---

## Analysis

### The central finding: resource abundance erases every strategic advantage

Every effect that was large and significant in `20260709_memory_vs_wm_v1` (sparse world,
`reposition=false`) is either gone or heavily compressed here:

| Effect | Scarce world | Dense + reposition world |
|--------|:------------:|:-------------------------:|
| JEPA survival advantage (corrected) | +52% vs baseline, p = 0.0008 *** | not significant, p = 0.79 |
| Memory survival penalty | −18% vs baseline, p = 0.0009 *** | not significant, p = 0.63 |
| Tedium suppression under JEPA | −66 to −70% | 0% (identical to baseline) |
| \|RPE\| ratio (JEPA / DISCRETE) | ~15× | ~4.3× |
| Engram \|delta\| ratio (JEPA / DISCRETE) | ~14× | ~4.3× |

The interpretation is consistent across every metric: when food is scarce and finite, *how* a
creature chooses what to interact with next has real behavioral consequences — better foraging
strategy translates into measurably longer survival and lower Tedium. When food is abundant and
self-replenishing, nearly any strategy finds food quickly enough that survival differences
disappear; nearly all creatures in every condition simply survive to the 60-minute time cap. The
RPE and engram-salience effects (which reflect the *mechanism* firing, not its downstream
behavioral consequence) still show JEPA producing a qualitatively larger prediction-error signal
than the tabular baseline — but even that signal is diluted, because a denser, ever-regenerating
world is itself more predictable at the population level, raising the DISCRETE baseline's own RPE
and narrowing the gap.

### Decision-tick counts reveal a real cost, just not a survival-relevant one

Baseline consistently completes more decision cycles than Memory or JEPA in the same wall-clock
window (5976 vs. 5629 and 5555, both p < 0.001) — memory lookups and JEPA inference are real,
measurable per-tick overhead. In the scarce world this overhead was outweighed by better
decisions; here, with survival no longer contingent on decision quality, the overhead shows up
only as fewer total decisions, with no corresponding cost or benefit in outcome.

### What this says about `consolidationEnabled`

Memory-only (condition 2) and Memory+Consolidation (condition 3) are statistically
indistinguishable on every metric measured here (survival, ticks, Tedium, cactus avoidance, food
quality). Consolidation neither helps nor hurts under resource abundance — consistent with the
scarce-world finding that consolidation's value is context-dependent (useful for adapting to a
*novel* world, as in `rotten_fruit_v1`; here the world isn't novel to any condition, memory-based
or not, since food is everywhere and unlimited).

---

## Summary Table

| Metric | Baseline | Memory | Mem+Consol | JEPA |
|--------|:-------:|:------:|:----------:|:------:|
| Lifetime (s, raw) | 3312 | 3202 | 3214 | 3296 |
| Lifetime (ticks) | **5976** | 5629 | 5887 | 5555 |
| Mean arousal | 13.15 | 13.31 | 13.48 | 13.23 |
| Tedium (mean) | 1.22 | 1.40 | 1.38 | 1.23 |
| EAT interactions | 873 | 673 | 795 | 645 |
| Cactus avoidance | 53.0% | 53.9% | 52.0% | 51.0% |
| Sleep episodes | **105** | 66 | 70 | 59 |
| \|RPE\| mean | 0.089 | 0.075 | 0.070 | **0.387** |
| Engram \|delta\| | 0.020 | 0.017 | 0.016 | **0.087** |
| Engrams | 106,158 | 91,676 | 100,420 | 90,220 |
| WORLD_MODEL % | 0.0% | 0.0% | 0.0% | **35.2%** |

---

## Conclusions

**H1: Not confirmed — the JEPA survival advantage disappears entirely under abundance,** not
merely shrinks. Raw lifetime is statistically identical to baseline (p = 0.96), versus a highly
significant 2.5× advantage in the scarce world.

**H2: Confirmed.** The memory-filter survival penalty seen under scarcity vanishes under
abundance (p = 0.63 ns vs. p = 0.0009 *** originally).

**H3: Not confirmed.** Memory+Consolidation is statistically indistinguishable from Memory-only
on every measured metric.

**H4: Confirmed, direction only.** JEPA's RPE and engram-salience signals remain measurably
larger than the DISCRETE baseline's, but the magnitude compresses from ~14–15× down to ~4.3×
under abundance.

**H5: Confirmed.** JEPA's dramatic Tedium suppression (66–70% in the scarce world) is completely
absent here (0% difference from baseline).

The overarching conclusion: **foraging-strategy sophistication (memory, world-model filtering,
JEPA-driven learning) only matters when resources are actually scarce.** Under abundance, the
mechanisms still measurably *fire* (RPE, engram salience, WORLD_MODEL selection rate) but produce
no detectable behavioral or survival advantage, because the environment no longer punishes a
naive strategy.

---

## Next Steps

1. **Re-collect `5_jepa_rpe_consolidation`.** All 5 trials of this condition showed zero
   sleep episodes and zero memory-consolidation activity — a likely bug in the
   `consolidationEnabled=true` + JEPA combination that needs its own investigation before this
   condition can be reported. A re-run with application-log preservation is in progress.

2. **Vary reposition rate rather than toggling it binary.** This experiment only tested the two
   extremes (finite food vs. infinite regrowth). A parametric sweep of regrowth rate would locate
   the scarcity threshold at which strategy starts to matter.

3. **Test at longer horizons.** With nearly every creature surviving to the 60-minute cap, this
   experiment's "lifetime" metric is largely uninformative (see the small-n caveat in Section 1).
   A much longer run (or a harder world) is needed to see whether strategy differences would
   eventually separate creatures even under abundance.

---

## Data Availability

```
ml/data_20260714_memory_vs_wm_dense_reposition/   — conditions 1-4 (5 trials × 10 creatures each)
```

Uploaded to `felipedreis/dl2l-experiments` under prefix `20260714_memory_vs_wm_dense_reposition/`.
Condition 5 (`5_jepa_rpe_consolidation`) is being re-collected separately and will be uploaded
once validated.
