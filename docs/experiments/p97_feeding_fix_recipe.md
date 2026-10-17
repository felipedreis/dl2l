# Experiment recipe — feeding at speed (#96) and seeded runs (#95)

Issue: [#97](https://github.com/felipedreis/dl2l/issues/97) (resolves #96, #95)
Plan: [`issue-97-arbiter-prerequisites.md`](../plans/issue-97-arbiter-prerequisites.md)
Report: `docs/reports/2026-10-17_p97_feeding_fix_report.md` (written against this recipe)

Pre-registration: written before any run. A changed step is changed here first.

## 1. Question and hypotheses

After the mouth-arc and contact fixes, do creatures keep eating throughout life? And does a
fixed seed reproduce the initial world and per-component draws?

- **H1 (feeding sustained).** In the fixed build, the legacy configuration shows at least one
  EAT in every life decile while hunger is above set point. Operationally, D = number of life
  deciles (of 10) containing an EAT. The claim holds if the lower bound of the 95% cluster-adjusted CI
  on P[D >= 9] among creatures is above 0.6, and the median D is >= 9.
- **H2 (vs. baseline, descriptive).** D exceeds the historical unseeded pre-fix p84 `legacy_nomem`
  data (mean 4.58, SD 0.94, 80 creatures, 16 trials). Mann-Whitney U on creatures, reported with
  Cliff's delta. This is a **historical control**, not a concurrent one: it ran on an older
  image, and the arc fix also moved *perception*, so a change in D is not attributable to the
  eating path alone.
- **H3 (seed replay).** Two runs with the same seed have identical initial object positions
  (from `run_info` seed + the first position rows). Full trajectories are not expected to match: Akka
  scheduling is nondeterministic. The deterministic action-sequence guarantee is covered by the
  `TestingCreature` unit test.

## 2. Design and sample size

- Arm `legacy_nomem` (`simulations/p84_legacy_nomem.conf`), **8 trials x 5 creatures = 40
  creatures**, seeds 9701..9708, run on the image built from this branch.
- Sizing (`analysis/diagnostics/p97_power.py`, baseline D mean 4.58, SD 0.94, ICC ~ 0 from 16 trials):
  comparison power is not binding (reaching D = 6.5 is d > 2). The binding constraint is the absolute
  claim: at p = 0.8, 8 trials give a 95% half-width of about 0.12 (6 give 0.14). ICC rests on 16
  trials, so treat it as rough.
- Seed replay (`experiments/p97_seed_replay.yml`): 2 identical conditions, 1 trial, same seed.

## 3. Procedure

1. `python3 scripts/validate_experiment.py` on both specs.
2. `cd ansible && ansible-playbook -i inventories/local run-experiment.yml -e experiment=p97_feeding_fix`
   (UI on :8080, data to HF under `p97/`), then the same for `p97_seed_replay`.
3. `PYTHONPATH=analysis python3 -m dl2l_analysis --experiment p97_feeding_fix`.

## 4. Analysis

- D per creature as in the power script, using `observed_s` for life length (creatures that
  are still alive at the cap are censored, not dead).
- EATs per life decile, fixed vs. baseline (figure).
- P[D >= 9] with a cluster-adjusted Wilson-style interval; trial-level bootstrap as a check.
- Approach-run outcomes (`p84_approach_check.py` logic): share of runs ending in an EAT.
- Hunger above set point is checked by the share of EATs occurring while `final_hunger` is
  above the set point; reported, not used as a filter if the data lacks it.

## 5. Decision rule

H1 holds if the criteria in section 1 are met. If not, the report says which part failed
(contact path vs. perception) and does not claim the fix. H2 is reported as descriptive whatever
the outcome.

## 6. Caveats stated up front

Historical control; the arc fix changes the A0 baseline for perception; ICC from 16 trials; replay
covers initial conditions and per-component draws only.
