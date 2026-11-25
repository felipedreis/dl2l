# Experiment recipe: the three changes of the Kalman plan added to A3, one at a time

Follows [`p117_factorial_recipe.md`](p117_factorial_recipe.md) · Plan: [`operant-kalman-and-stop-rule.md`](../plans/operant-kalman-and-stop-rule.md) · Spec: [`controllers-spec.md`](../plans/controllers-spec.md)
Experiment spec: `experiments/p117_kalman.yml` · Data: `ml/data_p117_kalman`, HF `p117_kalman/` · Code: `feature/a3-episodic`, image from `preview/a3-episodic`, pinned by `sha-` tag.
Written before the run. A changed step is changed here first, dated (2026-11-25).

## 1. Question

`p117_factorial` showed that A3 (arbitration) dies more than A5 (fixed order) in the plants world when the hand-built tendency is off, and `docs/reports/p117_factorial_mechanism.md`
traced it to the arbiter: the operant table hands untried cells in as lottery tickets, the stop rule declares the habit decisive on the top two, and a timed-out episode erases
the credit for approaching food. The plan fixes these three things. **Does each fix move A3, and does A3 with all three catch up with A5?**

## 2. Design

Four arms, all A3 in the plants world with the sleep lock off and the action tendency off (the `L0T0` cell of the factorial; the only cell where the controllers could be told apart
and A3 was not floor-limited: deaths 29 of 60 for A3, 15 of 60 for A5). Each arm adds one change to the one before:

| key | change on top of the previous arm | switch |
|---|---|---|
| `a3_base` | none: the control, re-run on the new image | defaults |
| `a3_mc` | a timed-out episode keeps the Monte Carlo return of every step | `timeoutZeroesApproach = false` |
| `a3_kal` | the operant table is the Kalman cell: drive-reduction sample, pooled prior, abstention as a pseudo-candidate | `operantEstimator = KALMAN` |
| `a3_all` | the stop rule is the probability that the best candidate wins the whole draw (theta stays 0.8) | `arbiterStopRule = WHOLE_DRAW` |

12 trials per arm, **seeds 10900-10911, the same as `p117_factorial`** (paired by initial world, and paired with its `a3_L0T0` and `a5_L0T0` cells); cap 60 minutes; one CCAD window; one image.
Configs `simulations/p117_kal_<arm>.conf`; a unit test pins that the arms differ only in these switches. **A5 is not re-run**: `p117_factorial/a5_L0T0` (same seeds, same world, defaults unchanged, pinned by tests) is
the reference; code drift is possible and is the reason `a3_base` is re-run.

Sample size: the factorial's trial-level contrast A3 - A5 in this cell was -390 s (8 of 12 seeds); the same 12 pairs are used. **Not powered** to read a small effect in d(H) or EAT rates; reported, not read.

## 3. Outcomes and readings (fixed now)

Unit: the trial (creatures share a world and a seed), paired by seed. **Primary: survival**: observed time per creature (censored at 3600 s), Kaplan-Meier curves, log-rank per contrast (creatures pooled within an arm), and the trial-level paired differences as the check. Contrasts: `a3_mc - a3_base`, `a3_kal - a3_mc`, `a3_all - a3_kal`, `a3_all - a3_base`, and `a3_all` against the factorial's `a5_L0T0` (descriptive: different image).
Secondary: share dead at the cap, d(H) over life, EATs per minute, controller share and budget, `source` shares (including the new `ABSTAIN_UNIFORM`), decisiveness distribution of the operant (the theta question), and calibration.
- **Cal (new rows):** operant one-step z per cell from `operant_updates` (`q_prior`, `se_prior`), separately for `basis_prior` OBSERVED and POOLED_PRIOR. Pass: mean z within +-0.1 and variance in [0.8, 1.25] on varying cells. The offline replay of p117_factorial gave 0.81 (observed) and 1.3 (pooled) at the frozen values.
- **Theta:** the share of decisions in which the operant reaches decisiveness 0.8 under `WHOLE_DRAW` (7% under the two-best rule in the factorial, where 90% sat at exactly 0.5). If it is near 0, A3 always escalates to episodic memory and `a3_all` is "episodic only whenever it is affordable"; that is read as such and is not a pass.

## 4. Hypotheses and what falsifies them (before data)

1. **`a3_mc` is no worse than `a3_base`.** The credit for approaching food comes back, so episodic memory can value approach. Falsified if `a3_mc` has more deaths with log-rank p < 0.05.
2. **`a3_kal` has fewer deaths than `a3_mc`**: untried operant cells no longer win draws as tickets, so the habit stops overriding episodic memory in the contact decisions with food. Falsified if no fewer deaths and the share of EAT at food contact among the operant's decisive decisions stays near the factorial's 7.6%.
3. **`a3_all` has deaths no greater than the factorial's A5 (15 of 60) and fewer than `a3_base` (29 of 60).**
4. **Overall falsification (the stop criterion for this line of work):** `a3_all` has no fewer deaths than `a3_base` (log-rank p > 0.2 and a non-positive trial-level mean improvement). Then the three changes do not repair arbitration in this world, and this branch ends without a further iteration.

I have no confident prior on 3; the prior run's direction (A5 > A3) is what the changes are meant to reverse, and the replay says only that the cell is calibrated, not that the arbiter will use it well.

## 5. Offline checks done before the run (2026-11-25)

- `mvn test` and `mvn package` green (see the report); new deterministic tests for the Kalman cell, abstention, the whole-draw confidence, the settings and the extraction columns.
- Replay of the Kalman cell over the 12 `a3_L0T0` trials of p117_factorial (36,931 one-step predictions on varying cells): tau2 frozen at 1e-4 (the plan's 8e-4 was too wide: z variance 0.71) and a pooled-variance floor of 0.16 (SE 0.4, from 8.0 to 1.3); results in the plan, last section.
- **Not done:** the stand-in simulation gate. The stand-in's candidate counts (2.4) are far from the real data's (10.3), so it cannot answer the theta question; it was not extended.

## 6. Procedure

`mvn package` green; push the branch and `preview/a3-episodic`; pin the `sha-` tag; `cd ansible && ansible-playbook -i inventories/ccad run-experiment.yml -e experiment=p117_kalman -e dl2l_image=ghcr.io/felipedreis/dl2l:sha-<commit>` (CEFET VPN up);
progress with `ccad-status.yml`; collect with `-e rescue=true`; upload (`dl2l_data.upload`); `PYTHONPATH=analysis python3.13 -m dl2l_analysis --experiment p117_kalman`; write section 7, the report in `docs/reports/`, and the diary entry.

## 7. Results

(to be written after the run)

## 8. Local pilot (2026-11-25): what was run instead of the CCAD campaign

The cluster was not reachable (no VPN), so the campaign was run **as a pilot on the laptop**: `experiments/p117_kalman_local.yml`, the same four arms and the first six seeds of the factorial (10900-10905), trials run
one after the other, **cap 20 minutes** (not 60), six trials per arm, local image built from this commit, UI on, data in `ml/data_p117_kalman_local`, HF prefix `p117_kalman_local/`. Everything in sections 3 and 4 applies with these changes:
- survival is censored at 1200 s, so the factorial's 60-minute numbers (29 of 60 and 15 of 60 deaths) are **not comparable**; the factorial reference is dropped, and `a3_base` is the only baseline;
- n = 6 per arm: the readings are directional, a log-rank p near 0.05 is not a pass, and falsification criterion 4 is read on the direction and the size of the difference, not on p alone;
- the pilot can fail to separate the arms because 20 minutes is short, which is not evidence for or against the hypotheses.
