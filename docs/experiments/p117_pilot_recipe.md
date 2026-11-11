# Experiment recipe — pilot of A3 and A5 (3 trials each), before the ladder campaign (#117)

Plans: [`issue-111-episodic-engram.md`](../plans/issue-111-episodic-engram.md), [`issue-112-engram-key-encoder.md`](../plans/issue-112-engram-key-encoder.md), [`issue-113-episodic-estimator.md`](../plans/issue-113-episodic-estimator.md), [`issue-114-budget-novelty.md`](../plans/issue-114-budget-novelty.md), [`issue-115-calibration.md`](../plans/issue-115-calibration.md), [`issue-116-fixed-order.md`](../plans/issue-116-fixed-order.md)
Pre-registration of the ladder: [`arbiter_ladder_recipe.md`](arbiter_ladder_recipe.md) (revision 1, section 8: this pilot sizes A3 and A5).
Spec: `experiments/p117_pilot.yml` · Data: `ml/data_p117_pilot`, HF `p117_pilot/` · Code: `feature/a3-episodic` (image from `preview/a3-episodic`, pinned by `sha-` tag).
Written before any run. A changed step is changed here first, dated.

## 1. Question

Does the episodic controller do what the pilot says A2 cannot: value APPROACH by what follows it, so the creature stops approaching gray apples as readily
as food? And is it used at all (budget, evidence)? A descriptive pilot: it sizes the campaign and checks the machinery, it does not adopt anything.

## 2. Design

Two arms, 3 trials each, seeds 10701-10703 (the pilot's, so the world is the one of the existing A0 / A1 / A2 pilot data), 5 creatures, CCAD, 120 minutes:
- `a3` (`simulations/arbiter_a3.conf`): A2 (`p107_gate2_a2.conf`: arbiter over the per-outcome operant table, thresholds 0.4 / 0.15, CAMPOS tendency lists) + the episodic controller
  (key lambda 0.5, m = 10, prior sigma 0.1, budget e >= 0.3, novelty 0.1 / sqrt(1 + n_eff)).
- `a5` (`simulations/arbiter_a5.conf`): A3 with the positional rule (episodic when affordable and every candidate has n_eff >= 3, else operant).

Comparison data, not re-run: A2 = `p107_operant` arm `a2n` (same code before this branch, same seeds and world; per-outcome operant table), A0 / A1 = `p107_pilot`.
The A2 arm was run on another day; the machine-dependence of cycle rate (recipe 2) is the reason the campaign re-runs every arm in one window.

## 3. Offline evidence before the run (2026-11-11)

- **Replay on the real pilot episodes** (`analysis/diagnostics/a3_episodic_replay.py`): the first version of the estimator (prior sigma 1) was decisive in **0 of 3000** queries, so A3
  would have equalled A2 plus noise; with prior sigma 0.1 it is decisive in 15-19% and prefers APPROACH toward food 59% of the time against 37% toward gray (A2: 50 / 50).
  The signal behind it (`a3_episodic_value_check.py`): mean V(G | H) of APPROACH toward food minus toward gray = **+0.032 in each of the three A2 trials**; 53% of the pilot's
  APPROACH steps are zeroed by timed-out episodes.
- **Stand-in simulation** (`analysis/diagnostics/a3_episodic_sim.py`; coarse: exact-key kNN, hunger only, budget ignored): pilot world, 15 lives of 150k decisions: food per 1k decisions
  A2 31.9, A3 41.0, A5 39.7; P(APPROACH | food in view) 0.50 / 0.63 / 0.63 against P(APPROACH | gray) 0.50 / 0.51 / 0.51; **gray eats per 1k** 20.8 / 20.7 / **33.4**; scarce world: no difference (all die within 1k decisions).

## 4. Readings (fixed now; per seed, no p-values at 3 pairs)

- **R0 (machinery, gate):** every `a3` / `a5` creature has rows in `episodic_engrams`; the episodic controller decides at least 1% of the multi-candidate decisions in `a3` (`deciding_controller`).
  If it decides under 1%, A3 is A2 in practice: stop, read `affordable_controller` (the budget: calm creatures have e < 0.3), `n_consulted` and the engram counts, and fix before any campaign.
- **R1 (use):** share of decisions the episodic controller decided, by life decile (A3, A5); share of decisions where it was affordable and consulted.
- **R2 (what A3 adds; the campaign's A3 criterion):** food share of EATs in the first 20% of life higher in `a3` than in `a2n` in all three seeds.
- **R3 (the mechanism):** share of seen-gray decisions that are APPROACH lower than seen-food decisions in `a3` (in A2 they are equal).
- **R4 (control):** `a3` against `a5`: d(H) over life, gray share of EATs, EATs per minute. Predicted from the stand-in: A5 eats more gray apples than A3 (positional use of thin episodic evidence).
- **R5 (calibration, measured only):** episodic z (mean and variance, by close type) from `arbitration_decisions`; operant statistic per trial (recipe 5) in `a3` and `a5`.
- **R6 (sizing):** SDs of the paired differences a3 - a2n and a3 - a5 on d(H) over life and on the early food share, on the 80% upper bound (`p107_sample_size.py` method); recipe 8 is revised if more than 16 trials are needed.

## 5. Procedure

1. `mvn test` green; push the branch and `preview/a3-episodic` (image trigger; if the workflow run wedges in `queued`, cancel fails and a new commit on the branch re-triggers it, as happened on 2026-11-11); pin the `sha-` tag of the commit that built the image and record it in section 7 with the run's `run_info`; `scripts/validate_experiment.py experiments/p117_pilot.yml`.
2. Submit to CCAD (VPN; submit, poll, `-e rescue=true`, patient retries); upload by hand with `dl2l_data.upload` (the playbook skips HF); check `run_info` seeds and observed times.
3. `PYTHONPATH=analysis /opt/homebrew/bin/python3.13 -m dl2l_analysis --experiment p117_pilot`; read a2n from `ml/data_p107_operant` for R2 and R6 by hand.

## 6. Predictions (before data)

R0 holds: the budget allows episodic memory whenever the creature has at least a few percepts and moderate arousal (the harness creature's e was 0.72-0.92), and the store fills (about 40k closed
steps per creature-life in A2, capped at 2000 per action). R1: the episodic share rises over life as the store fills (stand-in: 20-60% of decisions). R2 and R3 hold in direction;
magnitude is small (the replay's Q gap APPROACH - WANDER is 0.005-0.011 against a Thompson SE of 0.02). R4: A5 worse on gray eats than A3, d(H) not clearly different at 3 seeds.
R5: episodic variance of z not far from 1 with the blended sigma; timed-out APPROACH rows (G = 0) pull z up. Open: whether any of this moves d(H) at all, which is why the campaign is powered on the contrasts, not on this pilot.

## 7. Results

*(to be filled in after the run)*
