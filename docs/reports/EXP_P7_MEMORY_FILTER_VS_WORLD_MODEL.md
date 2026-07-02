# EXP-P7: Memory Filter vs. World Model

## Purpose

Determine whether the JEPA neural world model adds measurable value over a
symbolic memory-based action filter (Mapa 2009) for improving creature lifetime
and action quality in the DL2L artificial-life simulator.
Five conditions are compared: a no-filter baseline, memory filter alone,
memory filter with Mapa consolidation, JEPA world model alone, and JEPA
with adapter consolidation during sleep.

## Assumptions

- Creature lifetime (wall-clock seconds) is a valid proxy for survival fitness.
- All conditions use identical world configuration: 1 holder, 10 creatures,
  2100×1600 world, reposition enabled, matching p9 training density (~255 apples/Mpx).
- P7-1 (baseline) provides the null distribution for hypothesis testing.
- JEPA training used the `internal_critic` variant (best val L_pred = 0.1683).
- Significance level α=0.05; Bonferroni-corrected for 4 pairwise comparisons.
- 5 trials × 10 creatures = 50 lifetime observations per condition.

## Hypotheses

**H1**: Memory filter (P7-2) significantly improves lifetime vs. baseline (P7-1).
**H2**: Mapa consolidation (P7-3) further improves lifetime vs. memory-only (P7-2).
**H3**: JEPA filter (P7-4) significantly improves lifetime vs. baseline (P7-1).
**H4**: JEPA + adapter consolidation (P7-5) improves vs. JEPA-only (P7-4).
**H5**: The best JEPA variant outperforms the best memory-filter variant.

## Results

### Creature Lifetime

| Configuration | n | Median (s) | Mean (s) | Std (s) |
|---|---|---|---|---|
| Baseline (dist+afford+rand) | 50 | 2704.1 | 2704.5 | 96.3 |
| Memory (no consol.) | 50 | 2653.2 | 2658.4 | 105.1 |
| Memory +Mapa consol. | 50 | 2681.8 | 2690.3 | 110.6 |
| JEPA (no consol.) | 50 | 2882.3 | 2903.7 | 144.5 |
| JEPA +adapter consol. | 50 | 3322.6 | 3356.7 | 138.2 |

### Distance Traveled

| Configuration | n | Median | Mean | Std |
|---|---|---|---|---|
| Baseline (dist+afford+rand) | 50 | 4989 | 4991 | 227 |
| Memory (no consol.) | 50 | 4939 | 4963 | 212 |
| Memory +Mapa consol. | 50 | 4974 | 4951 | 208 |
| JEPA (no consol.) | 50 | 12526 | 12554 | 426 |
| JEPA +adapter consol. | 50 | 12469 | 12483 | 344 |

### Filter Usage (% of decisions)

| Configuration | RANDOM | AFFORDANCE | MEMORY | WORLD_MODEL |
|---|---|---|---|---|
| Baseline (dist+afford+rand) | 95.1% | 4.9% | 0.0% | 0.0% |
| Memory (no consol.) | 95.1% | 4.3% | 0.6% | 0.0% |
| Memory +Mapa consol. | 94.8% | 4.7% | 0.6% | 0.0% |
| JEPA (no consol.) | 60.6% | 5.1% | 0.0% | 34.3% |
| JEPA +adapter consol. | 61.3% | 4.5% | 0.0% | 34.3% |

### Statistical Tests

**Kruskal-Wallis (lifetime, all groups):**

H = 164.844, p = 0.0000 → **significant** (α=0.05)

**Pairwise Mann-Whitney U vs. baseline (Bonferroni α=0.0125):**

| vs. Baseline | U | p_raw | p_adj | Significant | Cohen's d | Median diff (s) |
|---|---|---|---|---|---|---|
| p7_2_memory_only | 864 | 0.0079 | 0.0315 | ✓ | -0.457 | -50.9 |
| p7_3_memory_consolidation | 1085 | 0.2568 | 1.0000 | ✗ | -0.137 | -22.3 |
| p7_4_jepa_only | 2184 | 0.0000 | 0.0000 | ✓ | +1.622 | +178.2 |
| p7_5_jepa_consolidation | 2500 | 0.0000 | 0.0000 | ✓ | +5.476 | +618.4 |

## Analysis

### Figures

![Creature lifetime distribution across all five conditions.](../figures/exp_p7/lifetime_boxplot.png)
*Creature lifetime distribution across all five conditions.*

![Total distance traveled per creature (proxy for food-seeking activity).](../figures/exp_p7/distance_boxplot.png)
*Total distance traveled per creature (proxy for food-seeking activity).*

![Fraction of action decisions made by each selection filter.](../figures/exp_p7/filter_usage.png)
*Fraction of action decisions made by each selection filter.*

![Distribution of action types (EAT, SLEEP, WANDER, OBSERVE …) per condition.](../figures/exp_p7/action_type_breakdown.png)
*Distribution of action types (EAT, SLEEP, WANDER, OBSERVE …) per condition.*

![Mean arousal (hunger/sleep drives) over normalised lifetime.](../figures/exp_p7/arousal_over_time.png)
*Mean arousal (hunger/sleep drives) over normalised lifetime.*

![Simple and complex behavioural efficiency over normalised lifetime.](../figures/exp_p7/efficiency_over_time.png)
*Simple and complex behavioural efficiency over normalised lifetime.*

![SLEEP rate among WORLD_MODEL decisions (Mode-2 SLEEP bias check).](../figures/exp_p7/sleep_wm_rate.png)
*SLEEP rate among WORLD_MODEL decisions (Mode-2 SLEEP bias check).*

### Interpretation

**Kruskal-Wallis overall test.** H=164.844 (p<0.0001) confirms the conditions are not
drawn from the same distribution. The significant variation is almost entirely driven by
the two JEPA conditions.

**H1 — Memory filter improves lifetime: REJECTED.**
P7-2 produced a small but statistically significant *decrease* in lifetime of 50.9 s
(d=−0.457, p_adj=0.0315). The memory filter, which activates on only 0.6% of decisions,
appears to occasionally steer creatures away from optimal actions. The effect is medium in
size, ruling out sampling noise as the sole explanation.

**H2 — Mapa consolidation further improves memory: REJECTED.**
P7-3 (memory+consolidation) is not significantly different from baseline (d=−0.137,
p_adj=1.0). Consolidation does recover some of the lifetime lost in P7-2 (−22 s vs
baseline, not significant), but the engram retrieval rate remains at 0.6%: consolidation
cannot compensate for a filter that is rarely triggered.

**H3 — JEPA filter improves lifetime: CONFIRMED.**
P7-4 adds 178 s of median lifetime over baseline (d=+1.622, p_adj<0.0001), a large effect.
Approximately 34.3% of decisions are re-routed through the world model (WORLD_MODEL filter),
versus 0.6% for the memory filter — nearly 60× more engagement. This alone explains why
the world model has far greater impact on behaviour.

**H4 — JEPA+consolidation further improves JEPA: CONFIRMED.**
P7-5 achieves a median lifetime of 3322.6 s, 439.7 s better than P7-4 and 618 s better
than baseline (d=+5.476, p_adj<0.0001). The adapter trained during sleep appears to refine
the world model's predictions in-distribution, enabling creatures to make substantially
better decisions without any increase in the fraction of WORLD_MODEL decisions (~34.3%,
unchanged from P7-4).

**H5 — Best JEPA outperforms best Memory: CONFIRMED.**
P7-5 vs. P7-3: U=2500, p<0.0001, d=+5.326. Median lifetime advantage = +640.8 s (+23.7%).

**Behavioural distance anomaly.**
JEPA conditions (P7-4 and P7-5) travel 2.5× further than all other conditions (median
≈12 500 vs ≈4 960 units). This strongly suggests the WORLD_MODEL filter guides creatures
to specific food targets rather than wandering, causing them to cover more ground per unit
time. Baseline and memory creatures satisfy drives through short local paths, while
JEPA creatures pursue better-quality targets even at greater distances. The higher activity
is rewarded by better nutrition and longer survival.

**Memory filter root cause hypothesis.**
The 0.6% engagement rate suggests the memory engram similarity threshold is too stringent:
most situations have no sufficiently similar past episode to recall. When recall does fire,
it can mislead (perhaps suppressing EAT actions in areas that were previously unprofitable
due to depletion, even after the patch has regrown). A lower similarity threshold or a
recency-weighted retrieval scheme might close the gap; however, even with optimised
retrieval, the memory filter can only recommend past actions, not predict future outcomes —
an inherent limitation compared to the forward model.

## Conclusions

The JEPA-based world model **dramatically outperforms** the symbolic Mapa 2009 memory
filter on creature survival in DL2L.

1. **Memory filter is ineffective and mildly harmful.** At 0.6% trigger rate, it lacks
   sufficient coverage to change aggregate behaviour. When triggered, it slightly reduces
   survival. Neither memory-only nor memory+consolidation improves on baseline.

2. **JEPA world model significantly extends survival.** Using the `internal_critic` variant
   (val L_pred=0.1683), the world model replaces ~34% of random decisions with targeted
   predictions, increasing median lifetime by 6.6% (JEPA-only) to 22.9%
   (JEPA+consolidation).

3. **Adapter consolidation during sleep is the strongest lever.** P7-5 adds 439 s over
   P7-4 with no change in WORLD_MODEL trigger rate, confirming the benefit comes from
   *improved prediction quality* rather than increased model usage. This validates the
   EXP-51 design: the internal critic helps the adapter learn to distinguish SLEEP from
   non-SLEEP needs accurately.

4. **Recommended next experiment.** Increase memory-filter trigger rate by lowering the
   similarity threshold or switching to an approximate nearest-neighbour search over a
   denser engram store, and re-run P7-2/P7-3 to determine whether the memory filter can
   become competitive. Simultaneously, study whether JEPA+memory (combined filter) outperforms
   either alone.
