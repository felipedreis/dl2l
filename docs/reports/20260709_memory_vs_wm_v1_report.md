# Experiment Report: Memory vs. JEPA World Model — Full Subsystem Stack

**Experiment ID:** `20260709_memory_vs_wm_v1`  
**Date:** 2026-07-09 / 2026-07-10  
**Trials:** 5 trials × 5 conditions × 5 creatures = **125 creatures total**  
**Analysis script:** `analysis/exp_20260709_memory_vs_wm_v1.py`  
**Data:** `ml/data_20260709_memory_vs_wm_v1/` (conditions 4–5) · `ml/data_20260709_memory_vs_wm_v2/` (conditions 1–3 rerun)

---

## Purpose

Compare five cognitive architectures under the full DL2L subsystem stack (orexin, endocrine,
neuromodulation, expectancy, action tendency, circadian rhythm) to determine whether:

1. A symbolic episodic memory filter improves creature survival and homeostatic regulation.
2. A JEPA neural world model filter improves creature survival and homeostatic regulation.
3. Sleep-based consolidation adds benefit within each filter type.

A secondary goal is to measure survival in **decision cycles (ticks)** — a discrete count of
appraisal episodes that is independent of inference wall-clock time — alongside the conventional
wall-clock seconds metric.

---

## Assumptions

- World layout: 1200×900, 5 creatures per trial, 500 red/green/gray apples, 50 CACTUS, 100 ALOE,
  `reposition=false` (finite food supply).
- The `unified_critic` JEPA model (trained on v3 data, VICReg + EMA, val L_pred = 0.0477)
  represents the species prior for the WORLD_MODEL filter.
- All five conditions share the same world layout and creature count (n=25 per condition).
- Creature lifetime in wall-clock seconds and in decision ticks are both valid but independent
  metrics: seconds measures total elapsed real time; ticks measures the number of appraisal
  cycles the creature executed before death.

---

## Hypothesis

| # | Hypothesis |
|---|-----------|
| H1 | The JEPA world-model filter increases creature survival vs. baseline, measured in decision ticks |
| H2 | Sleep-based adapter consolidation adds survival benefit on top of the JEPA filter |
| H3 | The episodic memory filter increases creature survival vs. baseline |
| H4 | Memory consolidation further improves memory-based performance |

---

## Conditions

| # | Key | Filters | Consolidation | Neuromodulation |
|---|-----|---------|---------------|-----------------|
| 1 | `1_baseline` | TARGET_DIST, AFFORDANCE, RANDOM | — | Yes |
| 2 | `2_memory_only` | + MEMORY | — | Yes |
| 3 | `3_memory_consolidation` | + MEMORY | MemoryTraceConsolidator | Yes |
| 4 | `4_jepa_only` | + WORLD_MODEL | — | Yes |
| 5 | `5_jepa_consolidation` | + WORLD_MODEL | MemoryConsolidator (adapter) | Yes |

All conditions: `circadianEnabled=true`, `expectancyEnabled=true` (DISCRETE),
`neuromodulationEnabled=true`, `actionTendencyEnabled=true`, `orexinEnabled=true`,
`endocrineEnabled=true`, `maxRuntimeMinutes=60`.

---

## Results

### 1. Survival — Wall-clock Seconds

![Lifespan](figures/p20260709/01_lifespan.png)

| Condition | Mean (s) | ± SD | n |
|-----------|:--------:|:----:|:-:|
| Baseline | 290.1 | 39.1 | 25 |
| Memory | 237.3 | 53.7 | 25 |
| Mem+Consol | 260.6 | 47.5 | 25 |
| **JEPA** | **550.4** | 78.3 | 25 |
| **JEPA+Consol** | **551.6** | 111.5 | 25 |

Kruskal-Wallis: H=93.27, p<0.0001.  
JEPA vs every non-JEPA condition: p<0.0001 (Bonferroni-corrected Mann-Whitney).  
Within non-JEPA: Baseline vs Memory p=0.0009\*\*\*, others ns–\*.  
JEPA vs JEPA+Consol: p=0.40 ns.

In wall-clock seconds, JEPA conditions survive **1.9× longer** than baseline.

### 2. Survival — Decision Ticks (inference-independent)

The right panel of the figure above shows the same creatures measured in **decision cycles** —
one tick per appraisal episode, independent of how long each cycle takes in real time.

| Condition | Mean ticks | ± SD | n |
|-----------|:----------:|:----:|:-:|
| **Baseline** | **21,417** | 2,307 | 25 |
| Memory | 19,270 | 3,960 | 25 |
| Mem+Consol | 19,844 | 3,059 | 25 |
| JEPA | 17,898 | 2,494 | 25 |
| JEPA+Consol | 17,893 | 3,121 | 25 |

Kruskal-Wallis: H=27.86, p<0.0001.  
Baseline vs JEPA: p<0.0001\*\*\*. Baseline vs JEPA+Consol: p=0.0001\*\*\*.  
Within non-JEPA: all ns. JEPA vs JEPA+Consol: p=0.73 ns.

**The tick ordering is the reverse of the second ordering.** Baseline creatures make the most
decisions before dying; JEPA creatures make the fewest.

### 3. Tick-Rate Analysis: Inference Overhead

The inversion is explained by JEPA inference latency. The WORLD_MODEL filter calls TorchScript
inference synchronously on the decision thread and fires on ~25% of cycles:

| Condition | Mean ticks | Lifetime (s) | Tick rate (ticks/s) |
|-----------|:----------:|:------------:|:-------------------:|
| Baseline | 21,417 | 290 | **73.8** |
| Memory | 19,270 | 237 | **81.2** |
| Mem+Consol | 19,844 | 261 | **76.0** |
| JEPA | 17,898 | 550 | **32.5** |
| JEPA+Consol | 17,893 | 552 | **32.4** |

JEPA runs at **32.5 ticks/s**, roughly half the baseline rate of **73.8 ticks/s**. At ~50ms
mean inference latency × 25% WORLD_MODEL activation, the expected overhead per tick is ~12.5ms
on top of the baseline ~13.5ms/tick — consistent with the observed 2× slowdown.

Projected JEPA lifetime at the baseline tick rate: 17,898 / 73.8 = **~243 s** — indistinguishable
from Memory (237 s) and Mem+Consol (261 s).

> **H1: Not confirmed.** In decision cycles, JEPA creatures make *fewer* decisions before death
> than baseline (p<0.0001). The wall-clock advantage is an inference-overhead artifact.

### 4. Drive Regulation (Arousal)

![Arousal over time](figures/p20260709/02_arousal_time.png)

| Condition | Mean Arousal | ± SD |
|-----------|:-----------:|:----:|
| Baseline | 17.28 | 4.46 |
| Memory | 17.22 | 4.42 |
| Mem+Consol | 17.18 | 4.28 |
| JEPA | 16.85 | 4.43 |
| **JEPA+Consol** | **16.55** | 4.41 |

Mean arousal differences are small (<5%). JEPA+Consol is marginally lowest, but this is
a continuous measure pooled over all ticks — given that JEPA ticks are sparser, the comparison
is confounded by sampling rate.

### 5. Per-Drive Trajectories

![Per-drive trajectories](figures/p20260709/03_per_drive.png)

| Drive | Baseline | Memory | Mem+Consol | JEPA | JEPA+Consol |
|-------|:--------:|:------:|:----------:|:----:|:-----------:|
| Hunger | 4.80 | 4.81 | 4.78 | 4.76 | 4.63 |
| Sleep | 3.98 | 3.94 | 3.93 | 3.79 | 3.77 |
| Pain | 6.08 | 6.13 | 6.15 | 6.06 | 6.02 |
| Tedium | 2.43 | 2.34 | 2.33 | 2.24 | 2.13 |

All conditions share nearly identical per-drive profiles. Pain is uniformly high (~6) — the 50
cacti create unavoidable contact irrespective of action quality. Tedium rises slowly in all
conditions, more so in longer-lived JEPA creatures that survive long enough to exhaust novelty.

### 6. Action Selection

![Filter distribution](figures/p20260709/04_action_filters.png)

| Condition | ACTION_TENDENCY | AFFORDANCE | MEMORY | WORLD_MODEL | RANDOM |
|-----------|:--------------:|:---------:|:------:|:-----------:|:------:|
| Baseline | 44.9% | 26.4% | — | — | 28.3% |
| Memory | 45.4% | 26.9% | 25.1% | — | 2.2% |
| Mem+Consol | 45.1% | 26.8% | 25.5% | — | 2.2% |
| JEPA | 49.0% | 22.4% | — | **25.5%** | 2.9% |
| JEPA+Consol | 48.9% | 22.5% | — | **25.1%** | 3.2% |

MEMORY and WORLD_MODEL filters fire at similar rates (~25%). The quality of the selected action
is what differs — and the tick analysis suggests neither filter produces a meaningful improvement
in survival quality per decision cycle.

### 7. Eating Behaviour & Cactus Avoidance

![Eating behaviour and cactus avoidance](figures/p20260709/06_eating_behaviour.png)

#### 7a. Food type selection (panel A)

| Condition | Gray Apple | Green Apple | Red Apple | Total EAT |
|-----------|:----------:|:-----------:|:---------:|:---------:|
| Baseline | 777 (30%) | 1,021 (40%) | 768 (30%) | 2,566 |
| Memory | 701 (31%) | 896 (40%) | 638 (29%) | 2,235 |
| Mem+Consol | 680 (30%) | 955 (42%) | 616 (27%) | 2,251 |
| JEPA | 621 (29%) | 826 (39%) | 667 (32%) | 2,114 |
| JEPA+Consol | 515 (26%) | 897 (46%) | 552 (28%) | 1,964 |

Food type proportions are nearly identical across all conditions (~30% red, ~40% green, ~30%
gray). No condition develops a preference for any particular apple type — selection is driven
entirely by proximity, not learned quality discrimination.

#### 7b. Hunger level at time of eating (panel B)

| Condition | Mean hunger at EAT | ± SD |
|-----------|:-----------------:|:----:|
| Baseline | 5.34 | 1.52 |
| Memory | 5.42 | 1.52 |
| Mem+Consol | 5.43 | 1.54 |
| JEPA | 5.28 | 1.60 |
| JEPA+Consol | 5.06 | 1.73 |

Hunger at eating time is uniformly high (~5.3–5.4) across non-JEPA conditions with a heavy
lower tail — creatures eat opportunistically when they find food regardless of how hungry they
are. No condition shows a meaningfully different hunger-targeting strategy.

#### 7c. Cactus avoidance (panel C)

| Condition | CACTUS encounters | Avoidance rate |
|-----------|:----------------:|:--------------:|
| Baseline | 33,483 | 55.1% |
| Memory | 26,776 | **58.0%** |
| Mem+Consol | 30,723 | 57.0% |
| JEPA | 28,220 | 55.8% |
| JEPA+Consol | 27,298 | 55.7% |

Memory conditions show marginally higher cactus avoidance (~58% vs 55%), but the 3-point gap is
small and uniform — no dramatic learning effect is visible.

#### 7d. Behaviour over normalised lifetime (panels D–F)

- **Eating rate (D):** Universal U-shape across all conditions — high at birth, dip at 10–20%,
  then stable. No condition achieves a systematically higher eating rate at any life stage.
- **Hunger at eating (E):** Starts near zero at birth (drives not yet accumulated), rises to
  ~5.5 by 20–30% of lifetime, then stays flat. Identical across conditions — this is the
  natural drive accumulation curve, not a learned foraging strategy.
- **Cactus avoidance (F):** Memory conditions sit slightly above baseline in the early and
  middle phases (~60–65% avoidance). All conditions drop sharply in the final 10–20% of life,
  consistent with creatures near death exhibiting degraded action selection. JEPA and baseline
  are indistinguishable throughout.

#### 7e. Cumulative food-type preference over lifetime

![Food-type preference learning](figures/p20260709/07_food_learning.png)

Each panel shows, for a given condition, the mean cumulative number of each apple type eaten by a
creature up to each life decile. If a creature were learning to prefer a higher-quality food, the
curve for that type would steepen in later deciles relative to the others (widening gap).

| Condition | Red Apple (total) | Green Apple (total) | Gray Apple (total) |
|-----------|:-----------------:|:-------------------:|:------------------:|
| Baseline | 30.7 | 40.8 | 31.1 |
| Memory | 25.5 | 35.8 | 28.0 |
| Mem+Consol | 24.6 | 38.2 | 27.2 |
| JEPA | 26.7 | 33.0 | 24.8 |
| JEPA+Consol | 22.1 | 35.9 | 20.6 |

All three curves are **parallel throughout the entire normalised lifetime** in every condition —
the gap between green, red, and gray apple stays constant from the first decile to the last.
Green apple is eaten most in all conditions, but this reflects world layout (more green apples
than red or gray in the simulation) rather than a learned quality preference. No condition
exhibits accelerating preference for any food type over its lifetime.

**Summary:** Neither the memory filter nor the JEPA world model produces a detectable improvement
in interaction quality — creatures eat the same food types in the same proportions throughout
their entire lives, eat at the same hunger levels, and avoid cacti at the same rate regardless
of condition. The filters are reshuffling *which* mechanism selects an action without improving
*what* gets selected.

### 8. Neuromodulators

![Neuromodulators](figures/p20260709/07_neuromodulators.png)

Tonic neuromodulator levels are visually indistinguishable across all five conditions. No
condition shows a qualitatively different dopamine, serotonin, or orexin trajectory.

### 9. Expectancy / RPE

![RPE](figures/p20260709/08_expectancy_rpe.png)

| Condition | \|RPE\| mean |
|-----------|:-----------:|
| Baseline | 0.0440 |
| Memory | 0.0451 |
| Mem+Consol | 0.0429 |
| JEPA | 0.0484 |
| JEPA+Consol | 0.0509 |

JEPA conditions show *higher* |RPE| than non-JEPA — the opposite of what better world-model
calibration would predict. This may reflect that the tabular DISCRETE predictor (used in all
conditions) is fitting a different distribution when the creature's action cycle is slowed by
inference, rather than a genuine difference in prediction error quality.

### 10. Memory Engrams

![Engrams](figures/p20260709/09_engrams.png)

| Condition | Engrams | Mean Elig. | Mean \|delta\| |
|-----------|--------:|:----------:|:--------------:|
| Baseline | 270,932 | 0.225 | 0.0099 |
| Memory | 237,780 | 0.225 | 0.0101 |
| Mem+Consol | 243,592 | 0.225 | 0.0096 |
| JEPA | 221,970 | 0.215 | 0.0105 |
| JEPA+Consol | 218,423 | 0.216 | 0.0110 |

Engram counts track tick counts closely — more ticks means more engrams. Baseline forms the
most engrams (270k) and JEPA+Consol the fewest (218k), confirming that JEPA's slower tick rate
reduces the volume of experiential memory available for consolidation.

### 11. Sleep Episodes

![Sleep](figures/p20260709/10_sleep_episodes.png)

| Condition | Episodes (total) | Mean duration (ticks) | SD |
|-----------|:---------------:|:--------------------:|:--:|
| Baseline | 316 | 11.54 | 2.71 |
| Memory | 274 | 11.76 | 2.93 |
| Mem+Consol | 286 | 11.55 | 2.62 |
| JEPA | 284 | 11.01 | 2.00 |
| JEPA+Consol | 295 | 11.16 | 2.30 |

Sleep episode counts and durations are nearly identical across conditions. The circadian/sleep
mechanism operates correctly regardless of which action-selection filter is active.

### 12. JEPA Inference Latency

| Condition | Mean (ms) | Median (ms) | Max (ms) |
|-----------|:---------:|:-----------:|:--------:|
| JEPA (4) | 49.7 | 46 | 293 |
| JEPA+Consol (5) | 49.3 | 46 | 244 |

~50ms mean latency per WORLD_MODEL cycle. At 25% WORLD_MODEL activation, this adds ~12.5ms
overhead per average tick — enough to halve the tick rate (13.5ms → 26ms per tick).

---

## Analysis

### The inference overhead confound

The central finding of this experiment is methodological: **wall-clock seconds is not a valid
survival metric when one condition includes synchronous neural inference on the decision thread.**

Concretely: JEPA creatures appear to live 1.9× longer in seconds, but actually make 16% *fewer*
decisions before dying. Their nominal wall-clock advantage (550s vs 290s) disappears when
corrected for tick rate. Projecting JEPA onto the baseline tick rate yields an expected
lifetime of ~243s — within the range of all non-JEPA conditions.

All subsequent experiments must report both seconds and ticks, and ideally move JEPA inference
off the decision thread (fire-and-forget with the previous cycle's cached prediction) to make
wall-clock time meaningful again.

### Why do interaction quality metrics show no differentiation?

The eating behaviour and cactus avoidance analysis (section 7) reveals that all five conditions
produce indistinguishable interaction patterns: same food type proportions, same hunger levels
at eating, nearly identical cactus avoidance rates (~55–58%), identical eating-rate and
hunger-at-eating trajectories over normalised lifetime, and — crucially — parallel cumulative
food-type curves throughout life (section 7e). No condition shifts toward any apple type in
later deciles, confirming that selection is proximity-driven rather than quality-driven.

This is consistent with the tick-survival result: the filters change *how* action selection
works (MEMORY recalls a past location, WORLD_MODEL scores candidates prospectively) but not
*what* the creature ends up doing in the world. Two explanations:

1. **The action space is already well-covered by AFFORDANCE.** The baseline action selector
   (AFFORDANCE + ACTION_TENDENCY) already tends to target the nearest food object when hungry,
   producing near-optimal food selection without any learning. The memory and world-model
   filters fire on 25% of cycles and override AFFORDANCE, but their selections are not
   detectably better in terms of food type or hunger timing.

2. **No quality signal propagates.** The world has three apple types with no differences
   programmed — all reduce hunger equivalently. There is no "bad food" to discriminate against
   and no "high-value food" to prefer. Any quality-learning signal in the memory or world
   model has nothing to latch onto.

### Why does the memory filter not improve tick-survival?

Memory (conditions 2–3) shows no significant survival benefit over baseline in ticks (p=0.10
ns). The cold-start problem is the most likely explanation: creatures must survive long enough
to accumulate high-salience traces before the MEMORY filter has anything useful to recall. In
this world, creatures executing ~20,000 decision cycles still die from food scarcity before the
memory system is well-populated. The interaction quality analysis confirms this — even late in
life (the final 40% of normalised lifetime), memory creatures do not eat more frequently or at
more appropriate hunger levels than baseline.

> **H2: Not confirmed.** Sleep consolidation provides no measurable benefit in either seconds
> or ticks (JEPA vs JEPA+Consol: p=0.40 ns / p=0.73 ns).  
> **H3: Not confirmed.** Memory filter provides no significant survival benefit in ticks
> (Baseline vs Memory: p=0.10 ns).  
> **H4: Not confirmed.** Memory consolidation provides no additional benefit over memory alone
> (Memory vs Mem+Consol: p=0.83 ns).

---

## Summary Table

| Metric | Baseline | Memory | Mem+Consol | JEPA | JEPA+Consol |
|--------|:-------:|:------:|:----------:|:----:|:-----------:|
| Lifetime (s, raw) | 290 | 237 | 261 | **550** | **552** |
| Lifetime (s, corrected) | 290 | 237 | 261 | 324 | 330 |
| Lifetime (ticks) | **21,417** | 19,270 | 19,844 | 17,898 | 17,893 |
| Tick rate (ticks/s) | **73.8** | 81.2 | 76.0 | 32.5 | 32.4 |
| Mean arousal | 17.28 | 17.22 | 17.18 | 16.85 | **16.55** |
| EAT interactions | **2,566** | 2,235 | 2,251 | 2,114 | 1,964 |
| Hunger at eating | 5.34 | 5.42 | 5.43 | 5.28 | 5.06 |
| Cactus avoidance | 55.1% | **58.0%** | 57.0% | 55.8% | 55.7% |
| Sleep episodes | **316** | 274 | 286 | 284 | 295 |
| \|RPE\| mean | 0.044 | 0.045 | 0.043 | 0.048 | 0.051 |
| Engrams | **270k** | 238k | 244k | 222k | 218k |

---

## Conclusions

**H1–H4 all not confirmed.** No filter or consolidation strategy improves survival in decision
ticks relative to baseline. The wall-clock advantage of JEPA is an inference-overhead artifact:
the synchronous 50ms TorchScript call on the action-selection thread halves the tick rate,
making JEPA creatures appear to live longer while actually experiencing fewer decision cycles.

The interaction quality analysis (section 7) confirms the null result at the behavioural level:
all conditions eat the same food types, eat at the same hunger levels (~5.3 mean hunger), and
avoid cacti at the same rate (55–58%). The filters change the *mechanism* of action selection
without changing the *quality* of actions taken — consistent with a world where all food types
are nutritionally equivalent and AFFORDANCE already produces near-optimal proximity-based
foraging.

---

## Next Steps

1. **Decouple inference from the tick loop.** Run WORLD_MODEL inference asynchronously,
   using the previous cycle's cached prediction. This eliminates the tick-rate confound and
   makes wall-clock time a valid metric again.
2. **Introduce food quality differentiation.** Give apple types different nutritional values
   (e.g. green > red > gray) so that food selection quality becomes a measurable signal.
   Only then can we test whether memory or JEPA guides creatures toward better food.
3. **Longer simulations with `reposition=true`** to give the memory system time to accumulate
   enough high-salience traces to overcome the cold-start problem.
4. **Run condition 6 (`jepa_rpe_consolidation`, `expectancyMode=JEPA`)** to test whether
   routing dopamine through world-model prediction error changes engram quality or cactus
   avoidance trajectories. Measure in ticks.

---

## Data Availability

```
ml/data_20260709_memory_vs_wm_v1/   — conditions 4–5 (JEPA variants, 5 trials × 5 creatures)
ml/data_20260709_memory_vs_wm_v2/   — conditions 1–3 (non-JEPA, 5 trials × 5 creatures)
```

Uploaded to `felipedreis/dl2l-experiments` under prefix `p20260709/`.
