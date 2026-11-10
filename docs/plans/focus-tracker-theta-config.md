# Plan: make the focus tracker's thresholds configurable and lower them (follow-up to the p107 gate)

Status: **draft, awaiting approval**. Date: 2026-11-05. Follows `docs/plans/arbiter-ladder-pilot.md` ("Stage 1 results", G1 FAILED,
option 1). Touches Java (PR) and changes the A1 and A2 definitions, so #107 and #108 need a note when it lands.

## Problem

`FocusTracker.Params.defaults()` (thetaOn 2.0, thetaOff 0.5, delta 0.3, thetaCrit 4.0, tMax 200, resumeWindow 10,
hStar 0.18, scale 1.0) is built in `FullAppraisal.preStart`; no config key reaches it. An episode opens when
`level - 0.18 >= thetaOn`, i.e. level >= 2.18. In the gate A2's highest drive level was 1.59 (hunger median 0.25, p90 0.45,
p99 0.83), so no episode ever opened and the operant table was never trained (0 of ~217,600 decisions per creature focused).
A1's hunger median is 1.39 (p90 2.20), so it crosses 2.18 in 12% of rows.

## Offline replay (first-order, not a result)

`analysis/diagnostics/p107_theta_replay.py` replays the tracker's `update` over each creature's `drives.parquet` series from the
gate (delta, thetaCrit, tMax, resumeWindow at defaults). Mean per creature (A2):

| thetaOn / thetaOff | opens | closed REGULATED | focused share |
|---|---|---|---|
| 2.0 / 0.5 (today) | 0 | 0 | 0.000 |
| 1.0 / 0.3 | 2.8 | 1.2 | 0.002 |
| 0.8 / 0.3 | 11.6 | 5.2 | 0.006 |
| 0.6 / 0.2 | 33.8 | 18.8 | 0.016 |
| 0.5 / 0.2 | 73.0 | 51.6 | 0.029 |
| 0.4 / 0.15 | 132 | 98.6 | 0.051 |

Caveats: (1) the replay is not calibrated: at the defaults on A1 it gives 1,292 opens per creature against 1,997 real episodes
over 5 creatures (about 400 each), so absolute counts are probably overstated by up to ~3x (the real tracker's cycle clock is not
the `drives` row index). (2) Once episodes open, the arbiter's behaviour changes and so do the drives; the replay cannot see
that. (3) The same lower thresholds applied to A1 raise its focused share from ~14-20% to 63-79% (thetaOn 1.0 to 0.5), with most
episodes ending TIMED_OUT: A1's focus statistic changes a lot.

## Change

1. `LearningSettings`: add `focusThetaOn`, `focusThetaOff` (doubles; defaults 2.0 / 0.5 so every existing conf is unchanged);
   validate `0 <= thetaOff < thetaOn`. Pass them to `FocusTracker.Params` in `FullAppraisal.preStart`. Tests: config parsing,
   defaults, validation, and a `FocusTrackerTest` case at the new values.
2. Record them in `run_info` so extraction shows which thresholds a run used.
3. Do not change delta, thetaCrit, tMax or resumeWindow here.

## Choosing the values (tuning, not the pilot seeds)

15-minute A2 runs on tuning seeds (not 10700-10703), 1 trial x 5 creatures each, thetaOn/thetaOff in
{0.8/0.3, 0.6/0.2, 0.5/0.2}; pick the weakest setting that meets the gate: >= 10 closed episodes per creature in >= 4 of 5
creatures, focused share >= 0.02, informed share >= 0.5 (the existing G1/G2). Values and seeds go in a recipe before running.
The gate (`p107_pilot_gate`) is then re-run with the chosen values for A1 and A2 (and A1/A2 keep identical thresholds).

## Open decisions

1. Same thresholds for A1 and A2 (recommended: they must match for the A1 vs A2 contrast) or A2 only.
2. Add the random-selection control (A2 with the table untrained) in the same stage.
3. Whether #108's A0 vs A1 focus-switch criterion is restated for the new thresholds.
