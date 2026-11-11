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
  sigma0 = nu0 = 1). Reason, same as `OperantEstimator`: with one neighbour, or neighbours that agree exactly, the paper's `sigma = s`
  is 0 and a single close memory would give SE ~ 0 ("sparse" would not be wide). The blend is 1 at n_eff = 1 and tends to s with evidence.
- No neighbours: `Estimate(0, 1, 1)` (the operant's unseen-cell prior), `n_eff = 0`.
- `Estimate` gains an optional `nEff` (NaN for controllers without one); the arbiter derives the novelty bonus from it (#114) and the
  fixed-order mode its evidence test (#116). The 3-argument constructor stays.
- Read-only; the store is the `EpisodicStore` facade.

## Done when (tests)
Close, agreeing, many neighbours -> narrow SE; one neighbour, far neighbours, or disagreeing neighbours -> wide SE; `n_eff` of ten
equal weights = 10, one close nine far ~ 1; values are read against `H_now` (same engram, other H, other V); bootstrap term and
zeroed APPROACH handled; empty store -> prior; deterministic; read-only.
