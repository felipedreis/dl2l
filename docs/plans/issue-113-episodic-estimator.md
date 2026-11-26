# Issue #113 — Episodic estimator (MFEC-style kNN)

Status: **plan, 2026-11-11.** Paper §4.3-4.4. Depends on #111, #112, #101.

`EpisodicEstimator implements ValueEstimator`. Per candidate action `a`: encode its key from (the candidate's perception, h_now);
take the `m = 10` nearest keys in the store of that action (Euclidean, precision weights 1); `omega_j = exp(-D_j^2 / 2 b^2)` with `b`
the median neighbour distance (equal weights when `b = 0`); `v_j = V(G_j | H_now) + bootstrapTerm_j` (HomeostaticValue.value);
`Q = sum(omega v)/sum(omega)`; `n_eff = (sum omega)^2 / sum omega^2`; `SE = sqrt(sigma^2 / n_eff + beta^2)`, `beta = L * Dbar`
(Dbar the omega-weighted mean distance, L = 0.5).

## Decisions
- **sigma** is the weighted standard deviation of the neighbours' values with the reliability correction `n_eff/(n_eff-1)`,
  **blended with the prior** as the operant estimator does (`sigma^2 = (nu0 sigma0^2 + (n_eff - 1) s^2) / (nu0 + n_eff - 1)`,
  nu0 = 1) with **sigma0 = 0.1**, on the scale of the values (see "Finding 2" below), not the operant's 1. Reason, same as `OperantEstimator`: with one neighbour, or neighbours that agree exactly, the paper's `sigma = s`
  is 0 and a single close memory would give SE ~ 0 ("sparse" would not be wide). The blend is 1 at n_eff = 1 and tends to s with evidence.
- No neighbours: `Estimate(0, 0.1, 0.1)`, `n_eff = 0`.
- `Estimate` gains an optional `nEff` (NaN for controllers without one); the arbiter derives the novelty bonus from it (#114) and the
  fixed-order mode its evidence test (#116). The 3-argument constructor stays.
- Read-only; the store is the `EpisodicStore` facade.

## Done when (tests)
Close, agreeing, many neighbours -> narrow SE; one neighbour, far neighbours, or disagreeing neighbours -> wide SE; `n_eff` of ten
equal weights = 10, one close nine far ~ 1; values are read against `H_now` (same engram, other H, other V); bootstrap term and
zeroed APPROACH handled; empty store -> prior; deterministic; read-only.

## Finding during implementation
With `b` the *median* neighbour distance the weights adapt to the spread: ten neighbours that are all far get `n_eff` close to 10, and
"one close, nine far" gives `n_eff` about 9.7, not about 1 as the paper's example says. The wide error bar of distant memory comes from `beta = L * Dbar`
alone (tested: `oneCloseAndNineFarIsWideThroughTheDistanceBiasNotThroughNEff`). Consequence for #114: the novelty bonus `0.1/sqrt(1 + n_eff)` is
large only when the action has few engrams, not when the nearby ones are far. Reported, not changed: the paper's formulas are implemented as written.

## Finding 2 (offline, before any cluster time): the prior spread has to be on the value scale
The first implementation copied the operant's `sigma0 = 1`. `analysis/diagnostics/a3_episodic_replay.py` replays the estimator on the real closed episodes of
`p107_operant` a2n (one creature, first half fills the store, 3000 queries from the second half at a perceived apple, candidates APPROACH and WANDER):

| variant | object | conf mean | decisive (conf >= 0.8) | best = APPROACH | SE of the best |
|---|---|---|---|---|---|
| sigma0 = 1 (first version) | food / gray | 0.538 / 0.539 | **0.000 / 0.000** | 0.586 / 0.373 | 0.110 / 0.107 |
| sigma0 = 0.1 (as implemented) | food / gray | 0.649 / 0.654 | 0.154 / 0.192 | 0.586 / 0.373 | 0.026 / 0.022 |
| paper (sigma = s, no prior) | food / gray | 0.663 / 0.679 | 0.183 / 0.241 | 0.586 / 0.373 | 0.024 / 0.019 |

`V(G | H)` is a deficit reduction (APPROACH toward food 0.02-0.05, toward gray 0.006, EAT on food 0.11-0.24), so a prior spread of 1 is two orders above
the signal: the controller would never have been decisive and A3 would have equalled A2 with extra noise. Mean neighbour distance is 0.02-0.03 (beta about
0.015), so the distance bias is not the problem. Direction: the estimator prefers APPROACH toward food 59% of the time against 37% toward gray (A2: 50% / 50%).
`a3_episodic_value_check.py` shows the underlying signal on all three trials: mean V of APPROACH toward food minus toward gray = +0.032 in each trial. 53% of the
pilot's APPROACH steps come from timed-out episodes, which zero them (paper 4.2); that halves the signal but does not remove it.
