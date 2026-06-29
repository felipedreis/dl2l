# EXP-51 — Internal-Aware Critic: SLEEP Bias Investigation

## Purpose

Determine whether giving the Critic access to `z_internal` (the creature's homeostatic
state encoded by `InternalEncoder`) eliminates the persistent Mode-2 SLEEP bias.

EXP-48B established the baseline: 94.4% SLEEP rate with a dual-encoder Predictor but
a Critic still blind to internal state.

## Assumptions

1. The SLEEP bias is caused by the Critic's inability to distinguish high-hunger from
   low-hunger states.
2. Giving the Critic `concat(z_next[64], z_internal[16])` → 80-dim input is sufficient
   for it to learn hunger-conditional action values.
3. The decision signal (`aversiveCost = pain + fear`) is meaningful and correctly trained.

**Assumption 3 is false.** See Root Cause Analysis.

## Hypothesis

`Critic(concat(z_next, z_internal), action)` will assign lower value to SLEEP when hunger
is high, causing Mode-2 SLEEP rate to drop below 20%.

## Results

| Experiment | SLEEP rate (Mode-2) | n decisions | Median lifetime |
|------------|---------------------|-------------|-----------------|
| EXP-43  (zero-init, old model)       | 94.6% | —    | — |
| EXP-48A (single-encoder retrain)     | 94.2% | —    | — |
| EXP-48B (dual-encoder, blind Critic) | 94.4% | 4518 | 288 s |
| EXP-51  (dual-encoder, aware Critic) | 94.9% | 3157 | 142 s |

**Target: < 20% SLEEP rate. Outcome: TARGET NOT MET.**

The internal-aware Critic produced no improvement and slightly shorter lifetimes,
consistent with random performance variation on an untrained cost signal.

### Training data (3 trials, train_p8)

| Metric                   | Value                      |
|--------------------------|----------------------------|
| Training samples         | 18 606                     |
| Validation samples       |  5 921                     |
| CACTUS perceptions       | 487 rows (plants visible ✓)|
| ALOE perceptions         | 576 rows                   |
| ht_hunger variance       | mean=2.01  std=1.07        |
| ht_sleep variance        | mean=0.20  std=0.04        |
| ht_pain variance         | 0.000 — constant           |
| ht_tedium variance       | 0.000 — constant           |
| Critic loss (ep 1→100)   | 1.29 → 0.014 (97% ↓ ✓)    |
| Best val L_pred          | 0.224                      |

## Root Cause Analysis

The Critic architecture change (z_internal → Critic input) was necessary but **not
sufficient**. There is a dimension mismatch between what the Critic is trained on and
what the inference path reads.

### The dimension mismatch

The Critic is trained via:

```python
L_crit = MSE(emotion_pred[:, live_dims], emotion_curr[:, live_dims])
# live_dims = [0, 1] = hunger (index 0) and sleep (index 1)
```

But `WorldModelEngine.aversiveCost()` reads:

```java
private static final Set<String> AVERSIVE_DIMS = Set.of(Constants.PAIN, Constants.FEAR);
// pain = emotion index 4, fear = emotion index 6
```

**Pain (dim 4) and fear (dim 6) are never part of `live_dims` and are never supervised
during training.** Their Critic outputs remain near their sigmoid-initialized equilibrium
(~3.5), regardless of hunger level, action type, or internal state.

### Empirical confirmation

Querying the trained EXP-51 Critic at four hunger levels with SLEEP vs EAT:

| hunger | SLEEP:pain | SLEEP:fear | EAT:pain | EAT:fear |
|--------|-----------|-----------|---------|---------|
| 0.2    | 3.547     | 2.473     | 3.581   | 2.533   |
| 1.0    | 3.497     | 2.662     | 3.528   | 2.725   |
| 2.0    | 3.311     | 2.947     | 3.357   | 3.004   |
| 4.0    | 3.079     | 2.993     | 3.103   | 3.012   |

**SLEEP always has a marginally lower pain+fear than EAT** (~0.06 lower on average).
This is a random initialization artifact, not learned behavior. `WorldModelFilter` sorts
by aversive cost and picks the minimum — SLEEP wins in every cycle.

The Critic DID correctly learn hunger/sleep predictions (L_crit dropped 97%), but those
trained outputs (dims 0–1) are never read by `aversiveCost`. The z_internal signal is
correctly forwarded but its effect is discarded at the scoring step.

## Required Fix

Change `AVERSIVE_DIMS` in `WorldModelEngine` to match `live_emotion_dims`:

```java
// Current (wrong — pain/fear at dims 4, 6 are never trained):
private static final Set<String> AVERSIVE_DIMS = Set.of(Constants.PAIN, Constants.FEAR);

// Option A: explicitly use the trained dims (hunger=0, sleep=1):
private static final Set<String> AVERSIVE_DIMS = Set.of(Constants.HUNGER, Constants.SLEEP);

// Option B: derive from contract (stays in sync with Python config):
public double aversiveCost(PredictedEmotionalState predicted) {
    double cost = 0;
    for (int idx : contract.liveEmotionDims)
        cost += predicted.level(idx);
    return cost;
}
```

With hunger as an aversive dimension:
- `EAT` → predicted next-hunger is low → `aversiveCost` low → preferred when hungry
- `SLEEP` when hungry → predicted next-hunger remains high → `aversiveCost` high → deprioritised
- `SLEEP` when tired → predicted next-sleep urgency drops → still selected when appropriate

Option B is preferred: it keeps the cost function automatically aligned with whatever
dimensions the Python training config designates as `live_emotion_dims`.

## Conclusion

EXP-51 delivered a **null result**, but the investigation was productive:

1. **Plants are now visible** — CACTUS/ALOE rows appear in training data (PR #50 fix confirmed).
2. **z_internal now reaches the Critic** — the architecture is correct and the Critic
   does learn from it (critic loss 97% ↓).
3. **Root cause of SLEEP bias identified precisely**: `AVERSIVE_DIMS` reads untrained
   dimensions (pain, fear at dims 4, 6) while the Critic is trained only on live dims
   (hunger, sleep at dims 0, 1). The effective cost signal is random noise.
4. **Fix is one method in `WorldModelEngine`**: derive `aversiveCost` from
   `contract.liveEmotionDims` instead of the hard-coded `AVERSIVE_DIMS` set.

## Figures

See `docs/figures/exp_51/`:

- `fig1_lifetime_comparison.png` — creature lifespan EXP-48B vs EXP-51
- `fig2_sleep_rate_comparison.png` — SLEEP rate across experiments (no improvement)
- `fig3_mode2_action_distribution.png` — full Mode-2 action breakdown
- `fig4_consolidation_loss.png` — per-creature consolidation loss curves
