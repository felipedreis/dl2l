# Experiment recipe — A3 and A5 in the plants world, crossed with the two hand-built priors (sleep lock, action tendency)

Follows [`p117_plants_recipe.md`](p117_plants_recipe.md) (section 6) · Plans: [`sleep-hysteresis-switch.md`](../plans/sleep-hysteresis-switch.md) · Ladder: [`arbiter_ladder_recipe.md`](arbiter_ladder_recipe.md)
Spec: `experiments/p117_factorial.yml` · Data: `ml/data_p117_factorial`, HF `p117_factorial/` · Code: `feature/a3-episodic` (image from `preview/a3-episodic`, pinned by `sha-` tag).
Written before the run. A changed step is changed here first, dated.

## 1. Question

`p117_plants` (A2, A3, A5; plants world; sleep lock and action tendency both off; 3 trials) separated the arms and put the **fixed order above arbitration**: deaths 7/15 (A5) vs 13/15 (A3),
log-rank p = 0.029, the opposite of the paper's main contrast. That run changed the world and both priors at once, with no control and n = 3. This run asks, at a sample size that can answer it:
(F1) does A5 > A3 replicate, and **in which prior settings**; (F2) what does each hand-built prior (the 10-cycle sleep lock, the hunger tendency lists) do to each controller in this world.

## 2. Design

Eight arms = controller {A3 arbitration, A5 fixed order} x sleep lock {L1 on, L0 off} x action tendency {T1 on, T0 off}, keys `a3_L1T1` ... `a5_L0T0`. Same plants world as `p117_plants`
(pilot world + 140 cactus + 140 aloe), CAMPOS tendency lists when on, thresholds 0.4 / 0.15, theta 0.8 / eps 0.03 / tau 1, one CCAD window, one image. Configs: `simulations/p117_fact_<arm>_L<l>T<t>.conf`
(world and cap in `p117_plants_world.conf`); a unit test pins that the eight differ only in the two switches and the controller flags.

- **12 trials per arm, seeds 10900-10911**, the same in every arm (paired by initial world); disjoint from the pilots (10701-10703), tuning (10710) and the ladder campaign (10800-10815).
- **Cap 60 minutes** (not the 120 of the earlier runs), to bound cluster time: 96 trials at about 10 concurrent (`short_qos`, 144 CPUs per user, 14 per trial). Survival is censored at 3600 s. Absolute times are not comparable with the 120-minute runs.
- Not included: A2 (it dies in about 5 minutes with the tendency off; its on/on cell is the ladder's own arm) and A0 / A1.

**Sample size** (`analysis/diagnostics/p107_sample_size.py` functions, A3 - A5 paired on observed time from `p117_plants`: mean -1918 s, SD 1009 with 2 df, 80% upper bound 2139): 5 trials for 80% power on the point SD, 12 on the upper bound, so
12 covers both. **d(H) is not powered** (needs 21 on the point SD, 84 on the bound) and is reported, not read. The campaign's 16 is not used here for cost.

## 3. Outcomes and readings (fixed now)

Unit: the trial (creatures share a world and a seed; trial means, paired by seed). **Primary: survival**: observed time per creature (censored at the cap), summarised per trial, with Kaplan-Meier curves and the log-rank test per contrast (creatures pooled within an arm, as `p117_plants`; the trial-level paired differences are the check).
Secondary: share of creatures dead at the cap, d(H) over life, EATs per minute, controller share and budget (`deciding_controller`, `affordable_controller`), calibration (operant statistic; episodic z).

- **F0 (is each cell readable?):** a cell is **ceiling-limited** if at least 11 of its 12 trials end with every creature alive at the cap, and **floor-limited** if all its creatures die within 600 s. Contrasts in such cells are reported, not read.
- **F1 (controller contrast, per cell):** A3 - A5 in observed time and deaths, 12 pairs per cell. The `p117_plants` result predicts A5 > A3 in `L0T0`.
- **F2 (prior effects, per controller):** each cell with a prior off minus the both-on cell (`L1T1`): `L0T1` (lock removed), `L1T0` (tendency removed), `L0T0` (both).
- **F3 (interaction, descriptive):** whether removing the tendency changes the lock's effect, from the four cells of each controller.
- **B (budget):** episodic share of decisions per cell (62-63% in `p117_plants`, 6% in the calm pilot world).
- **Cal:** operant statistic per trial in [0.8, 1.25] (recipe 5) where there are enough updates.

## 4. Predictions (before data)

1. **`L0T0` replicates `p117_plants`**: A5 survives longer than A3 (the prior run's per-seed signs were all in that direction).
2. **Tendency on (T1) rescues both controllers**: the hunger lists keep the creature foraging, as in the pilot world, so most `T1` cells are near the ceiling at 60 minutes and F1 is not readable there (F0). The earlier tendency experiment and the pilot world agree.
3. **The sleep lock matters little** next to the tendency: it overrides 4.1% of decisions when on. A small harm from the lock (lost time) is possible; no direction is predicted with confidence.
4. **Open and the reason for the run:** whether arbitration loses to the fixed order in the cells where the tendency is off only because the operant table is poor at consummatory choices without the tendency (the operant side wins the arbitration when its habit is more "decisive"), or for a reason that survives the tendency being on. I have no prior on this beyond the prior run.

## 5. Procedure

`mvn test` green; push the branch and `preview/a3-episodic` (the configs are baked into the image, so a new image is needed); pin the `sha-` tag; submit with the playbook; progress with `ansible/ccad-status.yml`;
collect with `-e rescue=true`; upload by hand (`dl2l_data.upload`); `PYTHONPATH=analysis python3.13 -m dl2l_analysis --experiment p117_factorial` (the module adds the factorial contrasts);
write section 6 and the diary entry.

## 6. Results

Full report: [`docs/reports/p117_factorial_report.md`](../reports/p117_factorial_report.md). Run 2026-11-12/13, image `sha-a2611e8`, all 96 trials complete (3 re-run after a start-up race, same seeds), HF `p117_factorial/`.

- **F0:** tendency-on cells are at or near the ceiling (1-2 deaths of 60; 10-11 of 12 trials with every creature alive); the four tendency-off cells are readable (none floor-limited by the 600 s rule).
- **F1 (A3 - A5):** never in A3's favour. Lock on / tendency off: -579 s, 12 of 12 seeds, paired p = 0.0002 (deaths 60 vs 58). Lock off / tendency off: deaths 29 vs 15 of 60, log-rank p = 0.0085; trial-level -390 s, 8 of 12 seeds, paired p = 0.22. Tendency on: no difference (ceiling).
- **F2:** removing the tendency is lethal with the lock on (deaths 60/60 A3, 58/60 A5; -3132 / -2508 s, 12 of 12 seeds); removing the lock alone does nothing with the tendency on (p = 0.5-0.6); removing both costs less than removing the tendency alone (29 and 15 deaths).
- **F3:** strong interaction: with the tendency off the lock overrides 42% of decisions and is the main cause of death; with the tendency on it overrides 2% and is harmless.
- **B:** episodic decides 27-32% with the tendency on (efficiency 0.27-0.28), 61-86% with it off.
- **Cal:** operant statistic in range with both priors on (A3 mean 0.99, A5 1.00); episodic z mean -0.03 to +0.07, variance 0.30-0.76 (conservative).

Predictions: (1) replicates on the creature-level test, not on the trial-level check; (2) holds; (3) **wrong** for the tendency-off cells (the lock is harmful there); (4) arbitration loses only where the tendency is off, the only place the controllers can be told apart in this world.
