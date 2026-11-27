# Recipe: pilot of the JEPA collection run

Epic #139 · Plan: [`jepa-planner-species-model.md`](../plans/jepa-planner-species-model.md) (stage 1) · Spec: `experiments/a6_collect_pilot.yml` · Data: `ml/data_a6_collect_pilot`, HF `a6_collect_pilot/`
Written before the run, 2026-11-27.

## 1. Question
Before the real collection run (training data for the world model): **does the transition log extract cleanly from a real run, and does `a3_kal` with exploration 0.15 visit
high-hunger states often enough to train on?** This is a pilot; nothing here is a result about the planner.

## 2. Design
2 trials, 5 creatures each, 20-minute cap, seeds 20000-20001 (disjoint from p117's 10900-10911), run locally with the UI on. One arm: `a3_kal` (Kalman operant + episodic memory, PR #132) in the plants world
(sleep lock and action tendency off), `transitionLoggingEnabled = true`, `arbiterEpsilon = 0.15` (the paper's floor is 0.03; the audit found the classic policy leaves some
actions rarely tried). Config `simulations/a6_collect_pilot.conf`.

## 3. What we read (fixed now; all descriptive, n = 2)
| # | Check | Pass |
|---|---|---|
| C1 | `decision_steps`, `decision_percepts`, `cycle_outcomes` are present and non-empty after extraction; `decision_steps` rows = `arbitration_decisions` rows per creature | all three hold |
| C2 | `cycle_outcomes` K per drive sums to the non-BASELINE K of `drive_outcomes` (the #100 conservation) | equal up to rounding |
| C3 | Lag between a K row and the decision of the act that caused it (matched on creature, action and target through `drive_outcomes`) | reported as a distribution; if more than 10% of K rows lag by 2 or more cycles, K is joined through `drive_outcomes` instead of the cycle stamp |
| C4 | Share of decisions at hunger >= 4 and >= 6 | >= 3% at hunger >= 4 is enough (assumption: the p117 episodic rows had 3.7%); below that, the collection run randomises food density per trial |
| C5 | Percepts per decision (median, p95) and rows per creature-hour for each new table | reported, to size the real run's volume |
| C6 | Death and survival at 20 minutes under exploration 0.15, against p117_kalman_local's `a3_kal` (same cap) | reported, not tested: the cost of exploring to the survival data |

## 4. Stop rule
If C1 or C2 fails, the logging code is fixed before anything else. If C4 fails, the collection design changes (food density per trial) before any cluster time. Otherwise the pilot's settings become the
collection run's, with 24 trials and a seed split by whole seed (16 / 4 / 4).
