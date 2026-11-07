# Experiment recipe — arbiter-ladder pilot, stage 2 (3 arms x 3 trials), CCAD

Plan: [`arbiter-ladder-pilot.md`](../plans/arbiter-ladder-pilot.md) ("Stage 2"; gate 2 passed 2026-11-06) · Spec: `experiments/p107_pilot.yml`
Data: `ml/data_p107_pilot`, HF prefix `p107_pilot` · Code: branch `feature/focus-theta-config` at `3ec1a4b`, image `ghcr.io/felipedreis/dl2l:sha-3ec1a4b`

Pre-registration: written before any run. A changed step is changed here first. This file is not committed (user instruction, 2026-11-06).

## 1. Purpose

Descriptive, not a hypothesis test. Measure, per arm, the between-trial and within-trial spread of the outcomes #107 will
pre-register (mean drive deficit d(H), feeding, survival, focus switches, A2 decision telemetry), so #107 can size the campaigns;
and produce real data on which #106's `analysis/experiments/arbiter_ladder.py` runs end to end. No pilot number enters a campaign result.

## 2. Design

Arms: `a0` (`arbiter_a0.conf`), `a1` (`p107_gate2_a1.conf`), `a2` (`p107_gate2_a2.conf`); A1 and A2 use the tuned focus thresholds
0.4 / 0.15 (set in those confs; A0 has no tracker). 3 trials per arm, seeds 10701-10703, the same three in every arm (paired by
initial world), 5 creatures per trial, 120-minute cap, the A0 world (100/100/300 mixed, respawn on). One CCAD SLURM array job per arm.
The trial is the independent unit; outcomes are creature values averaged within the trial.

Decisions I took because the user did not answer the plan's open questions (each reversible before launch, not after):
- **5 creatures per trial**, as the plan recommends (the issue says 3): keeps the world identical to the campaign's.
- **No `regulated_emotion` column**: A0's focus switches use the plan's `drives`-argmax approximation, and say so.
- **`backup: false`** (Parquet only): the raw Arrow dumps (about 6 GB per 120-minute trial, about 36 GB in all) would have to cross a VPN
  that drops on idle. The raw dumps are not preserved after the containers end.
- **No random-selection control arm** in this pilot.

## 3. Measures and sources

As the plan's "Measurements and their sources" table: d(H) over life and over a common 180 s window from `drives`; feeding rate from
`mouth_interactions` (EAT, RED/GREEN vs GRAY); survival from `creatures` (`observed_s`, `died`; censored at the cap); focus switches per
100 cycles from `actions.focal_drive` (A1, A2) and the `drives` argmax (A0, approximate); episodes from `episode_returns`; A2 decision
telemetry from `arbitration_decisions`. The z-calibration statistic is #115's and is not computed here.

## 4. Sample-size computation for #107 (after the data)

Paired differences over the 3 seeds, sized on the one-sided 80% upper bound of the SD (2.12 x s_D), A1 - A0 (focus switches, common-window
d(H)) and A2 - A1 (d(H) over life, non-inferiority); the margin is #107's decision (proposed: 0.06 on d(H), 10% and 20% of the A1 pilot
mean); a creature-level cross-check with `icc1` and `required_n`. Survival is not sized if A1/A2 are fully censored.

## 5. Procedure

1. Wait for the `Preview Image` workflow on `preview/focus-theta-config` to publish `sha-3ec1a4b`; check the tag is pullable.
2. `python3 scripts/validate_experiment.py experiments/p107_pilot.yml`.
3. `cd ansible && ansible-playbook -i inventories/ccad run-experiment.yml -e experiment=p107_pilot -e dl2l_image=ghcr.io/felipedreis/dl2l:sha-3ec1a4b`
   (submits and returns); later, on the VPN, `... -e rescue=true` until all nine trials have a `DONE` sentinel; it then syncs the data
   back and uploads (check the upload by listing the repo; run `dl2l_data.upload` by hand if the playbook skips it).
4. Check `run_info` (seed per trial, `focus_theta_on/off` 0.4 / 0.15 for A1 and A2) and that no trial died of an infrastructure fault.
5. Analysis, the #106 end-to-end run and the report are separate steps and will have their own plan notes.

## 6. Predictions (before data)

- P1: A0 creatures starve within about 4-8 minutes unless the current build changed that (p97: all died; p100: 5 of 5 alive at its 4-minute cap).
- P2: A1 survives most trials to the cap, but at the new thresholds one creature in five died in gate 2, so survival below 100% is plausible.
- P3: A2 survives to the cap with mean hunger about 0.3 and 130-170 closed episodes per creature, as in gate 2.
