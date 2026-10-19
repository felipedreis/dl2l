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

---

## Revision 1 (2026-10-18) — v1 failed H1; second intervention, arm `p97_feeding_fix_v2`

**What v1 showed** (8 trials x 5, seeds 9701-9708, arc + contact-range fix only): H1 fails.
Mean D 4.50 vs 4.58 pre-fix (Mann-Whitney p = 0.74), P[D>=9] = 0. Total EATs fell 40%; the
collapse after ~30% of life remained. These numbers were produced under the protocol above and
are reported as-is.

**Diagnosis (exploratory, from the v1 data — not pre-registered, flagged as such).**
- From ~30% of life, creatures stay inside ~10 px of movement (std of position by decile:
  185, 156, 99, 22, 13, 11, 14, 12, 9, 5 px; pre-fix 122, 146, 124, 87, 7, 6, ...) among ~10
  distinct objects, 92% of whose perceptions are GRAY_APPLE.
- The operant table learns P(EAT | GRAY_APPLE) -> 0 within ~50 s (eating gray gives no calories);
  P(EAT | RED/GREEN) stays ~37-50. Approaching gray therefore never ends in a meal.
- Code reading: the WANDER branch of `FullAppraisal.produceCortical` turns around
  `perception.angle`, the **bearing to the perceived object**, although its comment and the
  surrounding javadoc say "around current heading". Next to an object, WANDER is therefore a random
  walk *toward* it (+-30 deg) and the creature cannot leave. AVOID (+45 deg off the bearing) and
  APPROACH also keep it in the neighbourhood.

**Intervention v2.** WANDER turns around `creature.getVisionFieldPosition()` (the heading), as documented
(unit test `wander_near_an_object_turns_around_the_heading_not_the_objects_bearing`, fails
without the change). Nothing else changes. Same seeds, so v2 trial N shares its initial world with v1 trial N.

**Hypotheses for v2** (same operational definitions as H1/H2):
- **H4.** Position spread in the last 60% of life exceeds 3x that of v1 (median across
  creatures), i.e. the trap is gone.
- **H1'.** H1 as written, on v2.
- **H5 (v2 vs v1, paired by seed).** D is larger in v2; Wilcoxon signed-rank on the 8 trial means.

Decision rule: if H4 holds but H1' fails, the trap was real but not the only cause, and the
report says so; if H4 fails, the diagnosis is wrong and is reported as such. n = 8 trials is kept
(sized above; the SD for v2 is unknown, so intervals are the primary output).

Data: `experiments/p97_feeding_fix_v2.yml`, HF prefix `p97_v2/`.
