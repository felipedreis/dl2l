# EXP-51 — Internal-Aware Critic: SLEEP Bias Investigation

## Purpose

Determine whether giving the Critic access to `z_internal` (the creature's homeostatic
state encoded by `InternalEncoder`) eliminates the persistent Mode-2 SLEEP bias
established in EXP-48B (94.4% SLEEP).

## Assumptions

1. The SLEEP bias is caused by the Critic's inability to distinguish high-hunger from
   low-hunger states when choosing between actions.
2. Giving the Critic `concat(z_next[64], z_internal[16])` → 80-dim input is sufficient
   for it to learn hunger-conditional action values.
3. The training signal (`aversiveCost`) correctly reads the dimensions the Critic was
   trained on.
4. The Critic was trained on all regulated emotion dimensions (hunger, sleep, pain, tedium).

**Assumptions 3 and 4 were false.** See Root Cause Analysis.

## Hypothesis

`Critic(concat(z_next, z_internal), action)` will assign lower predicted cost to EAT
when hunger is high, causing Mode-2 SLEEP rate to drop below 20%.

## Results

| Experiment | SLEEP rate (Mode-2) | n decisions | Median lifetime |
|------------|---------------------|-------------|-----------------|
| EXP-43  (zero-init, old model)       | 94.6% | —    | — |
| EXP-48A (single-encoder retrain)     | 94.2% | —    | — |
| EXP-48B (dual-encoder, blind Critic) | 94.4% | 4518 | 288 s |
| EXP-51  (3 bugs fixed, this report)  | 93.6% | 3407 | 189 s |

**Target: < 20% SLEEP rate. Outcome: TARGET NOT MET.**

SLEEP rate declined by 0.8 pp after fixing 3 architectural bugs. The fixes are
correct and necessary, but the improvement is small because a deeper structural
problem remains: the training data is severely class-imbalanced.

## Three Bugs Found and Fixed

### Bug 1 — `HomeostaticRegulation.emotionalSnapshot()` missing pain and tedium

**Location:** `HomeostaticRegulation.java:59–64`

`emotionalSnapshot()` persisted only `hunger` and `sleep` to the `EmotionalState` JPA
entity, leaving `pain` and `tedium` at their Java default value of `0.0`.

```
Before fix — 171 688 rows:  pain=0.000 ± 0.000  tedium=0.000 ± 0.000
After  fix — 171 688 rows:  pain=1.747 ± 2.427  tedium=1.119 ± 1.046
```

Consequence: `prepare_dataset.py` detected zero variance for pain/tedium and set
`live_dims = [0, 1]` (hunger, sleep only) instead of `[0, 1, 4, 5]`.

**Fix:** Added `s.setPain(...)` and `s.setTedium(...)` to `emotionalSnapshot()`.

---

### Bug 2 — `WorldModelEngine.aversiveCost()` reading untrained dimensions

**Location:** `WorldModelEngine.java:38`

```java
// Before (wrong — fear at dim 6 is never in live_dims; hunger missing entirely):
private static final Set<String> AVERSIVE_DIMS = Set.of(Constants.PAIN, Constants.FEAR);
```

Hunger (dim 0) was never in `AVERSIVE_DIMS`. A creature with high hunger received the
same cost for EAT and SLEEP, so SLEEP won by initialization noise on every cycle.

**Fix:** Derive cost from `contract.liveEmotionDims` so the scoring always matches
what the Critic was trained on:

```java
public double aversiveCost(PredictedEmotionalState predicted) {
    double cost = 0;
    for (int idx : contract.liveEmotionDims)
        cost += predicted.level(idx);
    return cost;
}
```

With hunger in the cost: EAT (when hungry) → low predicted next-hunger → low cost →
selected. SLEEP (when hungry) → high predicted next-hunger → high cost → deprioritised.

---

### Bug 3 — `MemoryConsolidator.trainBatch()` passing wrong dimension to Critic

**Location:** `MemoryConsolidator.java:338`

During sleep-time adapter training, `z_internal` was correctly computed and
concatenated for the Predictor, but the Critic call used bare `nextZ` (64-dim):

```java
// Before (dimension mismatch — Critic expects 80-dim):
NDArray predDelta = criticTrainer.forward(new NDList(nextZ, actionBatch));
```

This caused `EngineException` on every consolidation batch in validation, silently
aborting all sleep episodes. Creatures never updated their adapters.

**Fix:** Build `criticInput = concat(nextZ, zInternal)` before the Critic call.

---

## Root Cause of Remaining SLEEP Bias: Training Data Class Imbalance

After all three bugs are fixed, the architecture is correct. But EAT is still only
0.4% of Mode-2 decisions. The cause is the **training action distribution**:

| Action   | Training samples | Share |
|----------|-----------------|-------|
| SLEEP    | 15 783          | 80.6% |
| AVOID    |  1 872          |  9.6% |
| APPROACH |  1 765          |  9.0% |
| EAT      |    155          |  0.8% |

With 155 EAT samples vs 15 783 SLEEP samples, the Critic sees 100× more SLEEP than
EAT. It cannot reliably predict a lower cost for EAT because the EAT signal is
drowned out. Mode-2 still picks SLEEP because EAT and SLEEP produce nearly identical
predicted emotion levels from the undertrained EAT path.

The training simulation (`train_p7_retrain.conf`) uses `enabledFilters = [TARGET_DISTANCE,
AFFORDANCE, RANDOM]`. When no food object is within perception range (the majority of
cycles in a large world), the only afforded actions are `{SLEEP, WANDER, OBSERVE}`.
RANDOM then picks SLEEP ~1/3 of the time, producing the 80.6% SLEEP dominance.

## Required Follow-Up Fix

**Apply class-balanced weights to the Critic loss** in `ml/jepa/train.py`:

```python
action_counts = df['action_type'].value_counts()
action_weight = 1.0 / action_counts  # inverse frequency
sample_weights = df['action_type'].map(action_weight)
L_crit = weighted_MSE(emotion_pred[:, live_dims], emotion_curr[:, live_dims], sample_weights)
```

Or equivalently, oversample EAT/APPROACH/AVOID rows so each action type is equally
represented in training batches. Without addressing the class imbalance, the Critic
will always converge to predicting near-average emotion levels for all actions, and
SLEEP will win by initiative.

## Conclusion

EXP-51 fixed 3 real architectural bugs that were masking the true root cause:

1. `emotionalSnapshot()` — pain/tedium now recorded → `live_dims = [0, 1, 4, 5]` ✓
2. `aversiveCost()` — derives from `contract.liveEmotionDims` → no untrained dims ✓
3. `MemoryConsolidator.trainBatch()` — Critic receives 80-dim input → consolidation runs ✓

SLEEP rate improved marginally (94.4% → 93.6%). The architecture is now correct.
The remaining SLEEP bias is driven by the 100:1 class imbalance (SLEEP vs EAT) in the
training data. The next issue should address class-weighted Critic training or a
more balanced data collection strategy.

## Figures

See `docs/figures/exp_51/`:
- `fig1_lifetime_comparison.png` — creature lifespan EXP-48B vs EXP-51
- `fig2_sleep_rate_comparison.png` — SLEEP rate across experiments
- `fig3_mode2_action_distribution.png` — full Mode-2 action breakdown
- `fig4_consolidation_loss.png` — per-creature consolidation loss curves
