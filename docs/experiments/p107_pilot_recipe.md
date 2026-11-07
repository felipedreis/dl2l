# Experiment recipe — arbiter-ladder pilot, stage 1 gate (#106 / #107)

Plan: [`arbiter-ladder-pilot.md`](../plans/arbiter-ladder-pilot.md) (approved 2026-11-04)
Spec: `experiments/p107_pilot_gate.yml` · Data: `ml/data_p107_pilot_gate`, HF prefix `p107_pilot_gate`

Pre-registration: written before any run. A changed step is changed here first.

## 1. Question

Descriptive, not a hypothesis test. Does A2 (arbiter over the operant table) open and close episodes and train its
table in the A0 world over a full-length run? Does A1 emit its telemetry? Does the #127 death close-out hold on a
real run? Criteria G1–G5 are exactly those in the plan ("Gate criteria"); they are not restated or loosened here.

## 2. Design

One trial per condition, seed 10700 (disjoint from all earlier and campaign seeds): `a1`
(`arbiter_a1.conf`), `a2` (`arbiter_a2.conf`), `a2_finite_food` (`p104_a2_finite_food.conf`), 5 creatures, cap 120 min
(30 min for `a2_finite_food`).

**Environment:** local (Mac, UI on), as in the plan. The gate was first attempted on the Pi cluster at the user's
request; the Pi run was abandoned (the NFS share served from the Mac wedged, and the Pi job's data never reached disk),
so it is not part of this experiment's data. G5 records the environment.

## 3. Procedure

1. `python3 scripts/validate_experiment.py experiments/p107_pilot_gate.yml`.
2. `cd ansible && ansible-playbook -i inventories/local run-experiment.yml -e experiment=p107_pilot_gate`.
3. Evaluate G1–G5 with the plan's queries over `ml/data_p107_pilot_gate/<cond>/trial_1/*.parquet`.
4. Results into the plan and `docs/research-diary.md`; verify the HF upload by file count.
5. Stop on FAIL or MARGINAL and bring the numbers.

## 4. Predictions (from the plan, recorded before data)

- P2: A1 survives to the cap; hunger bounded below ~3; 10–20% of decisions focused.
- P3: A2 opens at most a handful of episodes in 120 min (max hunger <= ~1.5); G1 is expected to fail.
