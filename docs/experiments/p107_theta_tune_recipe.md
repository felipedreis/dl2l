# Experiment recipe — focus-tracker threshold tuning for A2 (#107)

Plan: [`focus-tracker-theta-config.md`](../plans/focus-tracker-theta-config.md) (approved 2026-11-05)
Spec: `experiments/p107_theta_tune.yml` · Data: `ml/data_p107_theta_tune`, HF prefix `p107_theta_tune`
Code: branch `feature/focus-theta-config` (`focusThetaOn` / `focusThetaOff`, recorded in `run_info`); the image is built from it.

Pre-registration: written before any run. A changed step is changed here first.

## 1. Question

Descriptive tuning, not a hypothesis test. The p107 gate failed G1 because A2 never opened an episode (no drive reached
level 2.18). Which `thetaOn`/`thetaOff` is the weakest (largest `thetaOn`) setting at which A2 opens and closes enough episodes
to train its operant table? The chosen values are then used for the gate re-run and the stage-2 pilot.

## 2. Design

A2 (`arbiter_a2.conf`), tuning seed 10710, 1 trial x 5 creatures per setting, 30-minute cap (not 15 as in the plan: see
deviations), the same world as the gate. Settings, `thetaOn / thetaOff`: 0.8/0.3, 0.6/0.2, 0.5/0.2, 0.4/0.15. Same seed in
every setting (same initial world); trajectories still diverge (Akka scheduling). Everything else at defaults
(`delta` 0.3, `thetaCrit` 4.0, `tMax` 200, `resumeWindow` 10, `hStar` 0.18).

Deviations from the plan, both decided before any run:
- **30 minutes, not 15.** The plan's bar of >= 10 closed episodes is for a 120-minute run. The offline replay
  (`analysis/diagnostics/p107_theta_replay.py`) puts 0.6/0.2 at about 34 opens per creature in 120 minutes (an overestimate
  of up to ~3x), so about 8 (down to ~3) in 30 minutes but about 4 (down to ~1) in 15: too few to discriminate. 30 minutes with a
  quarter of the bar is the smallest run that can.
- **A fourth setting (0.4/0.15)** is added as a fallback in case 0.5/0.2 also falls short, because the replay overstates counts.
  The plan listed three.

## 3. Decision rule (fixed now)

Per setting, from `arbitration_decisions.parquet` and `episode_returns.parquet` (queries as in the plan's G1/G2):

- **closed episodes** = `count(DISTINCT episode_id)` of the creature in `arbitration_decisions` with a non-null `focal_drive`.
- **focused share** = share of decisions with a non-null `focal_drive`.
- A setting **qualifies** if in >= 4 of 5 creatures closed episodes >= 3 (a quarter of the gate's 10, for a quarter of its
  duration) **and** focused share >= 0.02.
- **Choice:** the qualifying setting with the largest `thetaOn`. If none qualifies, no setting is chosen; report the numbers and
  stop (next options: lower still, or the plan's option 3).
- **Reported, not decisive here:** informed share (second half of the run, focused multi-candidate decisions not fully tied),
  max/mean drive levels, survival, and A2's feeding rate against the gate's A2 (thresholds can change behaviour). The informed
  share >= 0.5 bar applies to the gate re-run, where the table has had 120 minutes to train.
- The decision uses this single seed; with 5 creatures per setting it is a tuning pick, not an estimate.

## 4. Procedure

1. `python3 scripts/validate_experiment.py experiments/p107_theta_tune.yml`.
2. `cd ansible && ansible-playbook -i inventories/local run-experiment.yml -e experiment=p107_theta_tune` (local, UI on).
   The playbook skips the HF upload (known `experiment_finished` host mismatch in `collect_upload`): run
   `PYTHONPATH=scripts python3 -m dl2l_data.upload --experiment p107_theta_tune --data-dir ml/data_p107_theta_tune --prefix p107_theta_tune`.
3. Check `run_info.focus_theta_on/off` equals each condition's setting and `seed = 10710`.
4. Apply section 3; results into the plan ("Stage 1b results") and `docs/research-diary.md`.

## 5. Predictions (before data)

- 0.8/0.3 does not qualify; 0.4/0.15 does; the choice is 0.5/0.2 or 0.6/0.2.
- Drives stay low in all four (max hunger < 2.18); A2 still feeds as well as in the gate.
