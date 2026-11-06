# Experiment recipe — arbiter-ladder pilot, stage 1 gate re-run (gate 2)

Plan: [`arbiter-ladder-pilot.md`](../plans/arbiter-ladder-pilot.md), [`focus-tracker-theta-config.md`](../plans/focus-tracker-theta-config.md)
Tuning: [`p107_theta_tune_recipe.md`](p107_theta_tune_recipe.md) · Spec: `experiments/p107_pilot_gate2.yml`
Data: `ml/data_p107_pilot_gate2`, HF prefix `p107_pilot_gate2` · Code: branch `feature/focus-theta-config`

Pre-registration: written before any run. A changed step is changed here first.

## 1. Question

The first gate failed G1 (A2 opened no episode; thresholds 2.0 / 0.5). The tuning run chose `focusThetaOn = 0.4`,
`focusThetaOff = 0.15` (the only setting meeting the per-creature rule: >= 3 closed episodes and focused share >= 0.02 in
4 of 5 creatures over 30 minutes; the setting-level reading of the rule would have chosen 0.6 / 0.2, decided against by the
user on 2026-11-06). Do the full-length gates now pass with those thresholds?

## 2. Design

Same three conditions as the first gate, now with the tuned thresholds in A1 and A2 alike (the keys are global): `a1`
(`arbiter_a1.conf`), `a2` (`arbiter_a2.conf`), `a2_finite_food` (`p104_a2_finite_food.conf`), each including the tuned
thresholds through `simulations/p107_gate2_*.conf`. Seed 10700 as in gate 1 (same initial world), 5 creatures, local Mac,
120 min (30 for `a2_finite_food`). Nothing else changes, so gate 1 and gate 2 are comparable per arm.

## 3. Gate criteria

Unchanged from the plan's G1-G5, with the plan's queries:
- **G1** (A2 opens and closes episodes): PASS if in >= 4 of 5 creatures `closed_episodes >= 10`, `focused_share >= 0.02` and
  `informed_share >= 0.5`; FAIL if `closed_episodes = 0` in >= 4 of 5; MARGINAL otherwise (treated as FAIL for proceeding).
- **G2** (the table informs focused choices): the `informed_share` term above.
- **G3** (A1 telemetry): 5 creatures, `focal_drive` non-null share > 0 for each, `episode_returns` present.
- **G4** (death close-out, `a2_finite_food`): `focused_decisions = steps` for every dead creature and `death_rows >= 1` where the
  last decision row has a focal drive; no mismatch warning in extraction.
- **G5**: `run_info.seed = 10700` and `focus_theta_on/off = 0.4/0.15` in every condition; raw size and extraction time recorded.

Reported, not criteria: A1's focus statistics and drive levels at the new thresholds against gate 1 (A1's definition has changed:
gate 1 had 17.5-22.1% of decisions focused, mean hunger 1.34, max 3.79), A2 drive levels and survival against gate 1.

## 4. Procedure

1. `python3 scripts/validate_experiment.py experiments/p107_pilot_gate2.yml`.
2. `cd ansible && ansible-playbook -i inventories/local run-experiment.yml -e experiment=p107_pilot_gate2` (local, UI on).
3. HF upload by hand (the playbook skips it, known `experiment_finished` host mismatch):
   `PYTHONPATH=scripts python3 -m dl2l_data.upload --experiment p107_pilot_gate2 --data-dir ml/data_p107_pilot_gate2 --prefix p107_pilot_gate2`.
4. Evaluate G1-G5; results into the plan and `docs/research-diary.md`. Stage 2 does not start from this recipe.

## 5. Predictions (before data)

- G1 passes: the tuning run gave 19-41 closed episodes per creature and informed share 0.96-1.0 in 30 minutes at these thresholds.
- A1 is focused far more often than in gate 1 (the replay put it at 80%+ of cycles), with most episodes TIMED_OUT or PREEMPTED.
- A2 drives stay low (max hunger < 2.18), as in gate 1 and in the tuning run.
