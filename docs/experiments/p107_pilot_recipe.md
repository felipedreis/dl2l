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

**Deviation from the plan:** the plan ran the gate on the local Mac; the user asked for the Pi cluster. The gate is a
qualitative check, so the environment does not change its criteria, but cycle rate on the Pis is unmeasured and may be
lower, which can reduce episodes per creature-hour and make G1 pessimistic. A FAIL/MARGINAL on G1 is therefore
reported with the observed cycles per creature-second, and is not by itself grounds for option 1–3 without that
context. Wall-clock per trial and raw size are recorded under G5. Stage 2 is not started by this recipe.

## 3. Procedure

1. `python3 scripts/validate_experiment.py experiments/p107_pilot_gate.yml`.
2. `cd ansible && ansible-playbook -i inventories/pi run-experiment.yml -e experiment=p107_pilot_gate`.
3. Evaluate G1–G5 with the plan's queries over `ml/data_p107_pilot_gate/<cond>/trial_1/*.parquet`.
4. Results into the plan and `docs/research-diary.md`; verify the HF upload by file count.
5. Stop on FAIL or MARGINAL and bring the numbers.

## 4. Predictions (from the plan, recorded before data)

- P2: A1 survives to the cap; hunger bounded below ~3; 10–20% of decisions focused.
- P3: A2 opens at most a handful of episodes in 120 min (max hunger <= ~1.5); G1 is expected to fail.
