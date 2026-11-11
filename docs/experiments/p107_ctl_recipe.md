# Experiment recipe — A2 with an untrained table (control), CCAD

Plan: [`arbiter-ladder-pilot.md`](../plans/arbiter-ladder-pilot.md), pilot results in [`p107_pilot_stage2_recipe.md`](p107_pilot_stage2_recipe.md)
Spec: `experiments/p107_pilot_ctl.yml` · Data: `ml/data_p107_pilot_ctl`, HF prefix `p107_pilot_ctl`
Pre-registration: written before any run. A changed step is changed here first.

## 1. Question

In the pilot A2 regulated far better than A1 (d(H) 0.16 against 1.24), but A2 differs from A1 in three ways at once: the arbiter replaces the
filter chain, `AFFORDANCE` and `RANDOM` are removed, and the operant table trains. Gate 1 hinted that training adds nothing (an A2 that never
opened an episode had the same mean hunger, 0.30). Does an A2 whose table never trains regulate as well as the trained A2 in the pilot?

## 2. Design

Arm `a2u`: `arbiter_a2.conf` with `focusThetaOn = 99` (above any drive level, levels peak near 7) and `focusThetaOff = 0.15`, so the focus
tracker never opens an episode, no episode closes and the table is never fed; the arbiter chooses from the untrained prior. Everything else as
the pilot's A2. 3 trials, seeds 10701-10703 (the pilot's), 5 creatures, 120-minute cap, local world as in the pilot; compared with the pilot's
A2 (trained, same seeds) paired by seed. CCAD, image built from `feature/focus-theta-config` (Java identical to the pilot's `sha-3ec1a4b`; only
the CCAD script, analysis and these control files differ).

## 3. Reading the result (fixed now)

Measure: d(H) over life per trial, differences A2 - A2u over the three seeds; also d(H) in the first 180 s, survival, EATs per minute and D.
- **Indistinguishable:** all three paired differences in d(H) over life lie within +-0.06 (the margin #107 proposes). Reading: training adds nothing
  measurable to regulation in this world, and A2's advantage over A1 comes from the selector and filter change.
- **Learning helps:** all three differences are below -0.06 (trained lower). Reading: the table contributes beyond the selector change.
- **Anything else** (mixed signs, one seed outside): inconclusive at 3 pairs; report the values.
No p-values (3 pairs). The margin is a sizing proposal, not a validated equivalence bound.

## 4. Procedure

1. `python3 scripts/validate_experiment.py experiments/p107_pilot_ctl.yml`; check `simulations/` includes resolve.
2. Push the branch to `preview/focus-theta-config`; wait for the `Preview Image` workflow; pin the new `sha-` tag.
3. `cd ansible && ansible-playbook -i inventories/ccad run-experiment.yml -e experiment=p107_pilot_ctl -e dl2l_image=ghcr.io/felipedreis/dl2l:sha-<new>`
   (submit-and-return), then `... -e rescue=true` on the VPN until the three trials are done; upload by hand with `dl2l_data.upload` (the playbook skips it).
4. Check `run_info`: seeds 10701-10703 and `focus_theta_on = 99`; check `episode_returns` is absent or empty (the table never trained) and every creature is
   observed to about 7,195 s unless it died.
5. `PYTHONPATH=analysis python3 -m dl2l_analysis --experiment p107_pilot_ctl`.

## 5. Predictions (before data)

- No episode opens (`episode_returns` absent, `actions.focal_drive` all null).
- d(H) over life of A2u within +-0.06 of A2's 0.16 in every seed (reading: indistinguishable), survival 15 of 15, mean hunger about 0.3.
