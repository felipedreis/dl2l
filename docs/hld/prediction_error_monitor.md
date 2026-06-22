# Online Prediction-Error Monitor — Spec

**Status:** Design only. Wired in Task 6.3 (`WorldModelFilter`).

---

## Purpose

The species model is trained on Mode-1 trajectories. Once `WorldModelFilter` steers
behaviour, the creature visits states the model has not seen. The monitor detects this
distribution shift so the filter can self-disable (degrading to Mode-1), fulfilling
invariant #3: the world model is always optional and never blocks the cognitive cycle.

---

## Metric

**Name:** `latentPredictionError`

**Definition:** For each engram `(s_t, a_t, s_{t+1})` formed during waking, compute the
mean-squared error in latent space between:

- the species predictor's output: `ẑ_{t+1} = Pred(Enc(s_t), a_t)`
- the actual next-state encoding: `z_{t+1} = Enc(s_{t+1})`

```
latentPredictionError = MSE(ẑ_{t+1}, z_{t+1})
                      = mean((ẑ_{t+1} - z_{t+1})²)
```

This is computed per-engram as it is formed (waking, not sleep). It requires one extra
forward pass through `Enc` for `s_{t+1}`, which is available at the point the engram is
committed to `MemorySystem`.

---

## Rolling estimate

Each creature maintains a per-creature exponential moving average (EMA):

```
EMA_t = α × latentPredictionError_t + (1 − α) × EMA_{t−1}
```

with `α = 2 / (N + 1)` for `N = 100` (equivalent window ≈ 100 engrams).

The EMA is stored in `WorldModelEngine` or the creature's adapter state, not a shared
structure. It resets to the baseline value at startup.

---

## Baseline

The median `latentPredictionError` over the held-out validation split, computed during
Task 2.3 (offline base training) and recorded in `model_contract.json` under the key
`"baseline_pred_error"`.

---

## Self-disable threshold

```
selfDisable = (EMA > 2.0 × baseline_pred_error)
```

The multiplier `2.0` is the starting value; expose it in `model_contract.json` as
`"ood_threshold_multiplier"` so it can be tuned without retraining.

When `selfDisable` is true for a given creature on a given cycle, `WorldModelFilter`
returns the input possibilities unmodified (Mode-1 fallback) for that cycle only — it
does not latch off permanently. Recovery happens naturally if the EMA drops back below
threshold.

---

## Wiring point (Task 6.3)

`WorldModelFilter.doFilter(List<Possibility> possibilities)`:

1. Read per-creature EMA from `WorldModelEngine`.
2. Read `baseline_pred_error` and `ood_threshold_multiplier` from the loaded
   `model_contract.json` (validated at boot per invariant #4).
3. If `EMA > multiplier × baseline` → return `possibilities` unmodified.
4. Otherwise → run CEM planning (subject to inference budget, invariant #5).

The EMA update itself happens in `WorldModelEngine.recordEngram(engram)`, called from
`MemorySystem` when a finished engram is committed (Task 4.2).
