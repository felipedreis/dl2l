# Plan: Rotten Fruit Aversion Experiment

## Goal

Determine whether DL2L creatures can learn to avoid interacting with a harmful food source
(ROTTEN_APPLE, caloricValue = −0.3) that was not present during training, and compare how
quickly and completely three conditions learn this aversion.

## Hypothesis

| # | Hypothesis |
|---|-----------|
| H1 | All conditions eventually reduce ROTTEN_APPLE interactions over the creature's lifetime as drives signal the negative outcome |
| H2 | JEPA+RPE+Consol learns aversion faster than baseline (earlier life deciles show lower rotten-apple interaction rate) |
| H3 | Memory+Consol also learns aversion but more slowly than JEPA+RPE+Consol |
| H4 | JEPA+RPE+Consol shows lower total rotten-apple consumption than both other conditions |

## Conditions

| # | Key | Description |
|---|-----|-------------|
| 1 | `1_baseline` | No learning — TARGET_DIST + AFFORDANCE + RANDOM |
| 3 | `3_memory_consolidation` | Memory filter + MemoryTraceConsolidator |
| 6 | `6_jepa_rpe_consolidation` | WORLD_MODEL filter + JEPA RPE expectancy + adapter consolidation |

All conditions use the full subsystem stack (orexin, endocrine, neuromodulation, expectancy,
action tendency, circadian).

## World Configuration

This is a **novel world** the creatures have not seen during any prior experiment:

- **Rotten apple present:** ROTTEN_APPLE replaces GRAY_APPLE (which has zero nutritive value,
  making it a neutral baseline — ROTTEN_APPLE with −0.3 is strictly harmful)
- **Quantities:** 500 RED_APPLE, 500 GREEN_APPLE, 500 ROTTEN_APPLE, 50 CACTUS, 100 ALOE
- **World size:** 1200×900, `reposition=false`, 5 creatures per trial
- **Runtime:** `maxRuntimeMinutes=120` (doubled from v1 to give learning time to accumulate)

The JEPA model was trained on v3 data which did not include ROTTEN_APPLE. This is intentional:
we want to measure zero-shot generalisation from the world model plus in-session adaptation.

## Key Measurements

- **Rotten apple interaction rate over normalised lifetime** (primary): does the rate drop
  in later life deciles? Faster drop = faster aversion learning.
- **Rotten apple % of total EAT events**: lower = better aversion.
- **Hunger at time of rotten apple eating**: if creatures learn aversion, they should eat
  rotten apples less even when very hungry (or stop entirely).
- **|RPE| after rotten apple events** (cond 6 only): does the JEPA predictor generate a
  large negative prediction error on first rotten apple contact?
- **Engram |emotion_delta| around rotten apple events**: does the negative outcome create
  high-salience engrams that drive future avoidance?
- **Survival (wall-clock seconds)**: overall fitness proxy.

## Sample Size

5 trials × 5 creatures × 3 conditions = 75 creatures total.

At n=25 per condition, with the effect size seen in the previous experiment (cactus avoidance
rate differences of ~10–13%), power is sufficient to detect a 2-decile shift in rotten-apple
avoidance trajectory with α=0.05 (Mann-Whitney, two-tailed).

## Experiment ID

`exp_rotten_fruit_v1` — prefix `p_rotten_v1/` on HuggingFace.

## Simulation Config Files to Create

```
simulations/rotten_fruit_v1_1_baseline.conf
simulations/rotten_fruit_v1_3_memory_consolidation.conf
simulations/rotten_fruit_v1_6_jepa_rpe_consolidation.conf
```

## Docker Compose Files to Create

```
docker/docker-compose-rotten-fruit-v1-1.yml
docker/docker-compose-rotten-fruit-v1-3.yml
docker/docker-compose-rotten-fruit-v1-6.yml
```

## Runner Script

`scripts/run_exp_rotten_fruit_v1.sh` — same pattern as `run_exp_20260709_jepa.sh`.

## Analysis Script

`analysis/exp_rotten_fruit_v1.py` — focused on:
1. Rotten apple avoidance learning curve (primary figure)
2. Food type proportion over normalised lifetime
3. Survival and drive regulation
4. RPE and engram salience around rotten apple events (cond 6)

## Report

`docs/reports/rotten_fruit_v1_report.md`

## Implementation Steps

1. Create simulation configs (3 files)
2. Create docker compose files (3 files)
3. Create runner script
4. Run experiment (5 trials × 3 conditions = 15 runs, ~2h)
5. Extract data with `exp_extract.py`
6. Write analysis script
7. Run analysis and generate figures
8. Write report
9. Upload data to HuggingFace under `p_rotten_v1/`
