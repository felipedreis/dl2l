# Pre-registration — the arbiter ladder (A0, A1, A2, A3, A5) — #107

Status: **draft for review, 2026-11-11.** Committed before any campaign. Changes after review go here first, dated.

> **Revision 1 (2026-11-11).** (a) One ladder campaign: A0, A1, A2, A3 and A5 run together, on one image and in one CCAD window, after a 3-trial A3 / A5 pilot. #108 and #109 are closed and absorbed by #117; their criteria are rows of §6. Reason: the pilot shows both gates by a wide margin (A0 vs A1: 15 / 15 dead against 1 / 15; A2 vs A1: d(H) 0.31 against 1.24-1.63), A3 is built on A2 whatever A2's result, and one window keeps every arm on the same machine. (b) The calibration statistic is computed per cell, over cells whose outcomes vary (§5): pooled, deterministic cells (EAT on a gray apple is always -1) collapse z and drag the variance below range.
Sources: arbitration paper draft (`docs/bib/uncertainty_arbitration.pdf`, §4 and §8.1), `docs/plans/2026-q4-research-plan.md` (decisions 1-5),
the pilot (`docs/reports/20261110_p107_pilot_report.md`, `docs/reports/p107_pilot_sample_size.md`) and the operant review
(`docs/plans/arbiter-proposal-review.md`, settled configuration in its section 10). Analysis module: `analysis/experiments/arbiter_ladder.py` (#106).

Items marked **[R]** are decisions the reviewer has to confirm; the recipe states the proposal and its reason.

## 1. Purpose and scope

Test whether arbitration between controllers by their uncertainty regulates drives better than each controller alone and than the same
controllers in a fixed order. In scope: A0 (baseline), A1 (focus tracker), A2 (arbiter, operant controller only), A3 (+ episodic controller),
A5 (A3's controllers in a fixed order). Out of scope here: A4 (key ablation), A6 (planner), A7 (emotional precision), §8.2 behavioural paradigms.

## 2. Shared configuration (every arm)

| Item | Value | Source |
|---|---|---|
| World | 1923 x 1610; 100 red, 100 green, 300 gray apples (caloric 0.2 / 0.5 / 0); eaten objects respawn at once | `simulations/arbiter_a0.conf` (#99) |
| Creatures | 5 per trial, one holder | `arbiter_a0.conf`; the pilot's choice over the issue's 3 (world identical to the pilot's, food competition unchanged) |
| Cap | 120 minutes of wall time | `arbiter_a0.conf` |
| Stack | legacy-minimal: action tendency on with **CAMPOS lists** (hunger = eat, approach, wander); neuromodulation, expectancy, orexin, endocrine, circadian, consolidation off | Q4 plan decisions 1-2; review §10 |
| Code | `feature/focus-theta-config` merged to `main`, including the affordance fix (#130); each campaign pins one image `sha-` tag, the same for all its arms | |
| Environment | CCAD (SLURM + Singularity), all arms of one contrast in the same submission window | cycle rate depends on the machine (pilot plan) |

## 3. Arms

| Arm | Configuration | Compared with |
|---|---|---|
| A0 | `arbiter_a0.conf`: filter chain TARGET_DISTANCE, AFFORDANCE (legacy operant table), RANDOM; no focus tracker | — (baseline) |
| A1 | A0 + focus tracker (`focalDriveEnabled`), thresholds 0.4 / 0.15 **[R]** | A0 |
| A2 | arbiter (`actionSelector = ARBITER`), operant controller only, per-outcome valence, key (object type, action); pre-filters action tendency + TARGET_DISTANCE; tracker on, thresholds 0.4 / 0.15 | A1 |
| A3 | A2 + episodic controller (#113: fixed hand-built key, λ = 0.5), efficiency budget (#114), novelty bonus | A2 |
| A5 | A3's two controllers in a fixed order (#116: episodic decides whenever it has evidence, operant otherwise); everything else as A3 | A3 |

**Focus thresholds [R].** 0.4 / 0.15 was chosen on tuning seed 10710 (p107 tuning, 2026-11-06) so that episodes open at the regulation level
an arbiter reaches; the paper's 2.0 / 0.5 opened none in A2 (gate 1). The operant table no longer needs episodes, but A3's episodic memory does,
so the proposal is to fix 0.4 / 0.15 for every arm that has a tracker (A1, A2, A3, A5), recorded as a constant with this source.

## 4. Constants fixed here (no tuning, see §7)

| Constant | Value | Source |
|---|---|---|
| Arbiter decisiveness θ, exploration floor ε, Thompson width τ | 0.8, 0.03, 1 | paper §4.5 (`Arbiter.Params.defaults()`) |
| Operant prior Q0, κ, prior σ0, ν0, count fading | 0, 1, 1, 1, 0.995 | paper §4.4 (`OperantEstimator`) |
| Operant sample | +1 if the reinforced drive fell, -1 otherwise; same events as the legacy table; fruit and plant targets only | review D2, D3 (Mapa 2009, `BBCSValuation`) |
| Focus tracker δ, θcrit, Tmax, resume window, h* | 0.3, 4.0, 200 cycles, 10 cycles, 0.18 | `FocusTracker.Params.defaults()`; paper Fig. 2 |
| Discount γ | 2^(-1/5) ≈ 0.87 | paper §4.2 (eligibility half-life of 5 cycles) |
| Episodic: neighbours m, kernel width b, bias L, novelty | 10, median neighbour distance, 0.5, 0.1 / sqrt(1 + n_eff) | paper §4.3-4.5, #113 |
| Efficiency budget: operant / episodic / world model | e ≥ 0, ≥ 0.3, ≥ 0.7, with e = the creature's normalised behavioural efficiency E(arousal)/10 | paper §4.5, #114 |
| Episodic key, store | λ = 0.5, fixed population statistics (`EngramKeyEncoder`), 2000 engrams per action (FIFO) | paper §4.3; `docs/plans/issue-112-engram-key-encoder.md`, `issue-111-episodic-engram.md` |
| A5 evidence rule | episodic decides when affordable and every candidate has n_eff ≥ 3; operant otherwise | #116 |
| d(H) set point | 0.18 per drive (hunger, sleep, pain, tedium) | `HomeostaticValue.defaults()` |

**Calibration [R].** Proposed: the SE rescale of §4.4 is **off** in A2, A3 and A5; calibration is *measured* (§5) and the rescale is evaluated as a
separate step after A3 / A5. Reason: it changes the controllers' error bars, so it is a mechanism of its own, and A2's adoption criterion asks
whether the uncorrected operant SE is honest.

**Correction (2026-11-11, during implementation).** Revision 1 wrote the novelty bonus as 0.1 / (1 + n_eff), copying the text extract of the paper; the rendered PDF (§4.5) has the square root, 0.1 / sqrt(1 + n_eff), as issue #114 says. The table above is corrected. The implementation also adds, as a deliberate departure to be reported, a prior blend in the episodic σ (σ0 = 0.1, one meal's worth of value; the operant's σ0 = 1 is on the valence scale and made the episodic controller never decisive in an offline replay on the pilot's episodes, `docs/plans/issue-113-episodic-estimator.md`) so that a single neighbour gives a wide SE; and the median-distance bandwidth keeps n_eff high for distant neighbours (distance acts through β), so the paper's "one close, nine far gives n_eff ≈ 1" does not hold (`docs/plans/issue-113-episodic-estimator.md`).

## 5. Outcomes

Unit: the trial (5 creatures share a world, a food supply and a seed); creature values are averaged within a trial. Arms are paired by seed.

- **Primary, every contrast:** d(H) over life, the mean of `sqrt(Σ_d max(0, h_d - 0.18)^2)` over a creature's `drives` rows.
- **Survival (guard, not powered):** Kaplan-Meier on `observed_s` / `died`, censored at the cap; reported with the log-rank test.
- **A1 contrast:** focus transfers per 100 cycles. A0 has no tracker: its value is the argmax-of-urgency approximation from `drives`
  (`arbiter_ladder.py`) **[R]**; the exact alternative needs a `regulated_emotion` column in `chosen_action_state` (Java, about half a day).
- **A2 calibration:** for every operant update, `z = (sample - Q) / sqrt(SE^2 + σ^2)` with Q, SE, σ the cell's estimate *before* the update
  (the previous row of the same cell in `operant_updates`, or the prior for its first update). Statistic, per trial: the variance of z in each
  (object type, action) cell with at least 30 updates whose samples are not all equal, averaged over those cells weighted by their update counts.
  Cells with constant outcomes (EAT on a gray apple: always -1) are excluded and reported separately with the pooled variance. Pilot (`p107_operant`
  `a2n`, 3 trials): red EAT 1.05, green EAT 1.25, weighted 1.15 (in range); gray EAT 0.001 (excluded); pooled 0.50-0.59.
- **A3 object choice early in life:** food share of EATs (red + green over all EATs) in the first 20% of each creature's life.
- **Reported, not criteria:** feeding rate (EATs per minute; lower bounds for survivors, see §9), life deciles with an EAT, controller share over
  life (A3, A5), decisiveness, share of decisions next to a gray apple, action shares.

## 6. Adoption criteria (paper §8.1; thresholds marked * fixed here)

Tests on trial means paired by seed; α = 0.05; no p-values are computed for the pilot.

| Arm | Adopt if |
|---|---|
| A0 | Baseline. Validation checks (from former #108), reported, not gates: hunger binds (some creature reaches 7), runs replay from a seed (identical initial world). "Feeding sustained across life" is expected to **fail** (pilot: all A0 creatures starve in 3.5-7.7 min, life deciles with an EAT 6.3) and is reported as a property of the baseline |
| A1 | Focus transfers per 100 cycles lower than A0 (paired t, one-sided), **and** survival not lower (log-rank, or KM median not below A0's) |
| A2 | Regulation not worse than A1: upper bound of the one-sided 95% CI of mean(d(H)_A2 - d(H)_A1) below the margin **M\* = 0.124** **[R]** (10% of the A1 pilot mean d(H) 1.24); **and** calibration: mean over trials of the per-trial z variance within **[0.8, 1.25]\*** |
| A3 | Food share of EATs in the first 20% of life higher than A2 (paired t, one-sided), **and** survival not lower than A2 |
| A5 | (Control.) Arbitration beats the fixed order: d(H) over life lower in A3 than A5 (paired t, two-sided) |

The A3 vs A5 contrast is the paper's main result; A3 vs A2 measures what episodic memory adds. Each adoption is its own decision; no correction
across arms. A failed criterion is reported, and the next arm is still run against the last adopted one when that is the design of the ladder (A3
against A2 even if A2 fails, because A3 adds a controller to A2).

**Margin [R].** The pilot's A2 (per-outcome table, `p107_operant` `a2n`) has d(H) 0.31, A1 1.24 (pre-fix) and 1.63 (fixed code): A2 is far
below A1, so the margin barely matters for the decision; 0.124 is the middle of the three options sized in the pilot (0.06 / 0.124 / 0.248).

## 7. Tuning budget

Equal for every arm, baseline included: **zero**. θ, λ and ε are fixed at the paper's values (§4) for every arm; no configuration search,
no held-out tuning seeds. Reason: the time budget, and that a zero budget is equal by construction. Deviation recorded: the focus thresholds
were tuned in the pilot (§3), on a seed disjoint from all campaign seeds, and apply identically to every arm with a tracker.

## 8. Sample size and seeds

**16 trials × 5 creatures per arm**, the paper's campaign size, all five arms in **one** campaign (`p117_ladder`, HF prefix `p117_ladder`), submitted together. From the pilot (`p107_pilot_sample_size.md`, power 0.8, α 0.05, paired trials,
sized on the point SD and on the one-sided 80% upper bound of the SD): A1 - A0 on the first-180-s d(H) needs 5 (15); A2 vs A1 non-inferiority
at M = 0.124 with equal true means needs 3 (7), at 0.06 7 (22). 16 covers every case but the 0.06 margin's upper bound. Caveats: the pilot's
A1 / A2 SDs come from 3 seeds; A2's pilot used the old (episode-trained) table, and the per-outcome table's spread is from `p107_operant` (3 seeds).
A3 and A5 have no pilot yet: a 3-trial pilot of each (seeds 10701-10703, pairing with the existing A0 / A1 / A2 pilot data) runs first, and if its
SD would need more than 16 trials, the campaign size is revised here before the campaign starts.

Seeds: **10800-10815**, the same 16 in every arm (paired by initial world). Pilot seeds (10700-10703), tuning (10710) and earlier runs are disjoint.

## 9. Known limitations, stated in the report

- `mouth_interactions` (and `body_states`) of creatures that survive to the cap hold whole 128-row blocks: EAT counts of survivors are lower bounds.
- A0's focus transfers are an approximation (§5) unless the `regulated_emotion` column is added.
- A0 starves within minutes, so d(H) over life compares a short life with a long one; the first-180-s d(H) is reported alongside.
- A2's habit cannot value APPROACH (no immediate outcome): it refuses gray apples but keeps approaching them (review §10). Expected to cost it
  regulation against a frozen table; this is the gap A3 is designed to fill.
- Cycle rate depends on the machine: every arm of a contrast runs on CCAD in one window, on one image.

## 10. Procedure per campaign

1. Pin the image (`ghcr.io/felipedreis/dl2l:sha-<main commit>`); validate the spec (`scripts/validate_experiment.py`).
2. Submit the five arms together on CCAD; collect with `-e rescue=true`; upload to HF (`p117_ladder`); check `run_info` (seed, thresholds) and observed times.
3. `PYTHONPATH=analysis python3.13 -m dl2l_analysis --experiment <campaign>`; apply §6; report per CLAUDE.md step 5 (Purpose, Assumptions,
   Hypothesis, Results, Analysis), including the deviations and §9.

## 11. Open items for the reviewer

1. Focus thresholds 0.4 / 0.15 for every arm with a tracker (§3).
2. Calibration measured, rescale off (§4). (Its statistic is decided: per cell, Revision 1.)
3. A0's focus transfers: approximation, or add the `regulated_emotion` column before #108 (§5).
4. Non-inferiority margin M = 0.124 (§6).
5. Zero tuning budget (§7).
6. 16 trials × 5 creatures, seeds 10800-10815 (§8).
