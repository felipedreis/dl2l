# Plan: `analysis/experiments/arbiter_ladder.py` (#106)

Status: **in progress, 2026-11-07**. Issue #106: "runs end to end on a pilot's data and produces the report skeleton". Measures and sources
are those of `docs/plans/arbiter-ladder-pilot.md` ("Measurements and their sources"); the first pilot data is `ml/data_p107_pilot`
(3 arms x 3 trials x 5 creatures, seeds 10701-10703, spec `experiments/p107_pilot.yml`). Python only; no Java.

## What it computes (per creature, then per trial, the unit)

| Measure | Arms | Definition |
|---|---|---|
| d(H) over life (primary) | all | per `drives` row, `sqrt(sum_d max(0, h_d - 0.18)^2)` over hunger/sleep/pain/tedium; mean per creature |
| d(H), common window | all | same, rows within 180 s of `born_time` (A0 lives about 4-8 min) |
| Feeding | all | EATs per minute of `observed_s` (all, RED+GREEN, GRAY); D = life deciles containing an EAT |
| Survival | all | Kaplan-Meier on `observed_s` / `died`, creatures alive at the cap censored; Fisher on deaths and log-rank, pairwise A0-A1, A1-A2 |
| Focus switches per 100 cycles | A1, A2 | `actions.focal_drive` by row: transfers (drive to another drive) and opens (none to drive), counted separately |
| Same, comparable across arms | all | **approximation:** per `drives` row, argmax of the urgencies (`h - 0.18`, none if all are 0); transfers and opens per 100 rows. The cycle's regulated emotion is not persisted (plan: gap), so A0 uses this |
| Episodes | A1, A2 | per creature: count (distinct `(creature_key, episode_id)`; ids restart per creature), steps per episode, close-type mix |
| A2 decision telemetry | A2 | focused share, informed share (late half, focused multi-candidate, not fully tied), source mix, share where the executed action is not the chosen one, controller share by life decile |
| Calibration (descriptive) | A2 | `z = (realised_value - executed_q) / sqrt(executed_se^2 + executed_sigma^2)` by `close_type`: n, mean, variance, RMS. The [0.8, 1.25] statistic and any rescale are #115's |
| Paired by seed | A0, A1, A2 | per measure, per trial index (= seed), differences A1-A0, A2-A1, A2-A0: values, mean, SD (2 df) and the 80% upper bound `2.12 * s_D`. No p-values at 3 pairs (minimum attainable two-sided Mann-Whitney/sign p is above 0.05) |

Outputs: figures in `docs/reports/figures/p107_pilot/`, tables to stdout and `docs/reports/p107_pilot_report.md` (ReportBuilder skeleton:
Purpose / Assumptions / Hypotheses / Results / Analysis). The narrative (Analysis) is written afterwards by hand.

## Out of scope

The sample-size computation (#107), calibration statistic and rescale (#115), controller share beyond A2's single controller (A3+, #113),
and the exact A0 focus-switch statistic (needs the `regulated_emotion` column). Extension points: `ARMS`, `CREATURE_METRICS`.

## Check

`python3 -m unittest analysis.diagnostics.test_arbiter_ladder` (pure functions on synthetic frames), then the module on `ml/data_p107_pilot`:
`PYTHONPATH=analysis python3 -m dl2l_analysis --experiment p107_pilot` (the spec gains `analysis: {module: arbiter_ladder}`).
