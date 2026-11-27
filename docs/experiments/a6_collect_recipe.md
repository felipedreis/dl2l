# Recipe: JEPA collection run (training data for the world model)

Epic #139 · Plan: [`jepa-planner-species-model.md`](../plans/jepa-planner-species-model.md), stage 1 · Pilot: [`a6_collect_pilot_recipe.md`](a6_collect_pilot_recipe.md)
Spec: `experiments/a6_collect.yml` · Data: `ml/data_a6_collect`, HF `a6_collect/` · Image: `preview-a6-planner` (needs the branch pushed as `preview/a6-planner`), pinned by `sha-` tag.
Written before the run, 2026-11-27.

## 1. Question
This run **produces the world model's training data**; it tests no hypothesis. Its job is to cover the states a creature can be in, above all the hungry ones, with the transition log
(`decision_steps`, `decision_percepts`, `cycle_outcomes`) that the predictor and the cost head are trained on.

## 2. Design
`a3_kal` (Kalman operant + episodic memory) in the plants world, sleep lock and action tendency off, exploration floor 0.15, transition log on, 60-minute cap, 5 creatures per trial.
Three food densities (the pilot's C4 failed: 2.3% of decisions at hunger >= 4, 3% needed; the cause is that creatures with food in reach are rarely hungry):

| key | apples (red + green + gray) | note |
|---|---|---|
| `dense` | 100 + 100 + 300 | the plants world of p117 |
| `mid` | 50 + 50 + 150 | half |
| `sparse` | 25 + 25 + 75 | a quarter |

Cactus and aloe (140 each) are unchanged. **8 seeds, 20000-20007, the same in each density: 24 trials, 120 creatures.** The seeds are disjoint from the p117 campaigns (10900-10911). The pilot used 20000-20001 at 20 minutes; they are re-run here at 60 minutes, and the pilot data is not pooled.

**Split by whole seed, across all densities at once:** train 20000-20005 (18 trials), validation 20006 (3), offline test 20007 (3). A held-out seed is held out in every density, so no world of the validation or test set was seen in training.
The stage-3 campaign uses other seeds again. No test manipulation (hazard, held-out object type, devaluation) is in this world.

## 3. What we read (fixed now)
| # | Check | Pass |
|---|---|---|
| D1 | Tables extract in every trial; `decision_steps` = `arbitration_decisions` per creature | all trials |
| D2 | Share of **train-seed** decisions at hunger >= 4, and at >= 6 | >= 3% at hunger >= 4 pooled **and** in each density; report >= 6 |
| D3 | Rows per (object type, action) in the train seeds | every affordable pair has >= 10 000 rows (assumption: enough for a small network) |
| D4 | Deaths and survival per density | reported; if a density kills nearly all creatures within 10 minutes it adds little late-life data and is flagged |
| D5 | `cycle_outcomes` vs non-baseline `drive_outcomes` | equal for creatures that die; for survivors a tail difference of the known export block artefact (pilot: 0-19 rows of about 27 000) is tolerated |

## 4. Stop rule
If D2 fails in the pooled train data, a fourth, sparser density is added and run before training. If D1 fails, the logging is fixed and the run repeated. Otherwise the train seeds go to #134's training, the
validation seed picks the checkpoint and the SE calibration constants, and the offline test seed is used once, in #135.

## 5. Cost
24 trials x 60 minutes on CCAD, about one window (the Kalman campaign ran 48 trials in about 11 h at 10 concurrent trials, so about 5-6 h here). Volume from the pilot: about 30 MB of new tables per
trial-hour (5 creatures; the pilot's three new tables were 19 MB for two 20-minute trials), well below the quota problem of earlier runs.
