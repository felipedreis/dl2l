# EXP-51 — Internal-Aware Critic: SLEEP Bias Elimination

## Purpose

Determine whether giving the Critic access to `z_internal` (the creature's homeostatic
state encoded by `InternalEncoder`) eliminates the persistent Mode-2 SLEEP bias.

EXP-48B established the baseline: 94.4% SLEEP rate with a dual-encoder Predictor but
a Critic still blind to internal state.

## Assumptions

1. The SLEEP bias is caused by the Critic's inability to distinguish high-hunger from
   low-hunger states (confirmed by EXP-48B null result).
2. Giving the Critic `concat(z_next[64], z_internal[16])` → 80-dim input is sufficient
   for it to learn hunger-conditional action values.
3. Training data with plant perceptions (CACTUS/ALOE now collidable after PR #50) enriches
   the experience space and improves generalisation.

## Hypothesis

`Critic(concat(z_next, z_internal), action)` will assign lower value to SLEEP when hunger
is high, causing Mode-2 SLEEP rate to drop below 20%.

## Results

| Experiment  | SLEEP rate (Mode-2) | n decisions |
|-------------|---------------------|-------------|
| EXP-43 (baseline, old model) | 94.6% | — |
| EXP-48A (single-encoder retrain) | 94.2% | — |
| EXP-48B (dual-encoder, blind Critic) | 94.4% | — |
| EXP-51 (dual-encoder, aware Critic) | 92.8% | 4680 |

Target: < 20% SLEEP rate.
Outcome: **TARGET NOT MET ✗**

## Analysis

See figures in `docs/figures/exp_51/`:
- `fig1_lifetime_comparison.png` — creature lifespan EXP-48B vs EXP-51
- `fig2_sleep_rate_comparison.png` — SLEEP rate across all experiments
- `fig3_mode2_action_distribution.png` — full Mode-2 action breakdown
- `fig4_consolidation_loss.png` — per-creature consolidation loss curves
