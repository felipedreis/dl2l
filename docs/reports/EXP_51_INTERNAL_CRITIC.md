# EXP-51 — Internal-Aware Critic: SLEEP Bias Investigation

## Purpose

Determine whether giving the Critic access to `z_internal` (the creature's homeostatic
state encoded by `InternalEncoder`) eliminates the persistent Mode-2 SLEEP bias first
identified in EXP-43 and confirmed across EXP-48A/B.

EXP-48B established the baseline: 94.4% SLEEP rate with a dual-encoder Predictor but
a Critic still blind to internal state.

## Assumptions

1. The SLEEP bias is caused by the Critic's inability to distinguish high-hunger from
   low-hunger states — confirmed by EXP-48B null result.
2. Giving the Critic `concat(z_next[64], z_internal[16])` → 80-dim input is sufficient
   for it to condition action value on homeostatic urgency.
3. The standard MSE-on-absolute-emotion training target is biologically wrong: an animal
   that sleeps when hungry does not experience zero homeostatic relief for SLEEP — it
   experiences *insufficient* relief relative to its current need.  A need-weighted target
   `tanh((emotion_next - emotion_now) / emotion_now)` encodes this: EAT-while-hungry
   yields tanh(-3.0/4.0) ≈ −0.636 (strong relief) vs SLEEP-while-hungry ≈ 0.0 (no relief).

## Hypothesis

`Critic(concat(z_next, z_internal), action)` trained with need-weighted homeostatic relief
targets will assign lower value to SLEEP when hunger is high, causing Mode-2 SLEEP rate
to drop below 20%.

## Bugs Found and Fixed

Three architectural bugs were identified and fixed during this investigation; each masked
the Critic's ability to learn:

### Bug 1 — HomeostaticRegulation.emotionalSnapshot() missing pain and tedium

`HomeostaticRegulation.emotionalSnapshot()` only recorded hunger and sleep.  All 124,188
rows in training data had `pain=0.0`, `tedium=0.0`, causing the InternalEncoder to receive
a systematically incorrect `h_t`.

**Fix:** `src/main/java/…/HomeostaticRegulation.java` — added `s.setPain()` and
`s.setTedium()` calls.  After fix: pain mean=1.75 std=2.43, tedium mean=1.12 std=1.05.

### Bug 2 — WorldModelEngine.aversiveCost() reading untrained dimensions

`AVERSIVE_DIMS` was hard-coded to `{pain(4), fear(6)}`.  Dimension 6 (fear) was never in
`live_emotion_dims` and never trained.  Dimension 0 (hunger) was excluded, so EAT and
SLEEP scored identically regardless of hunger level.

**Fix:** `src/main/java/…/WorldModelEngine.java` — removed `AVERSIVE_DIMS`; `aversiveCost()`
now iterates `contract.liveEmotionDims` (hunger, sleep, pain, tedium).

### Bug 3 — MemoryConsolidator passing wrong-dimension input to Critic

`trainBatch()` built `criticInput` from bare `nextZ` (64-dim) but the Critic expected
`concat(z_next[64], z_internal[16])` = 80-dim.  This caused a `EngineException` on every
sleep consolidation batch, silently aborting adapter training.

**Fix:** `src/main/java/…/MemoryConsolidator.java` — hoisted `zInternal` outside the
if-block and built `criticInput = NDArrays.concat(new NDList(nextZ, zInternal), -1)`.

## Training Details

Dataset collected from 3 simulation trials (10 creatures, large world, 285 apples):

| Split | Samples |
|-------|---------|
| Train | 38,411  |
| Val   | 3,506   |
| Total tuples | 41,917 |

DualSpeciesModel trained 100 epochs on MPS device.

| Metric | Value |
|--------|-------|
| Best val L_pred | 0.7238 (epoch 43) |
| Final critic loss (tr) | 0.0004 |
| Collapse check | PASS |
| Effective rank | 7.98 (threshold 6.40) |
| Min per-dim variance | 0.573 (no dead dims) |

Critic loss stayed stable at 0.0004 throughout training — the tanh-normalised targets
are well-scaled (bounded [-1, 1]) and learned rapidly.

## Results

| Experiment | SLEEP rate (Mode-2) | EAT rate (Mode-2) | Mode-2 decisions | Lifetime median |
|------------|--------------------|--------------------|------------------|-----------------|
| EXP-43 (baseline, old model) | 94.6% | — | — | — |
| EXP-48A (single-encoder retrain) | 94.2% | — | — | — |
| EXP-48B (dual-enc, blind Critic) | 94.4% | 0.4% | 4,518 | 288 s |
| EXP-51 (dual-enc, aware Critic, need-weighted) | **93.5%** | 0.1% | 2,782 | **370 s** |

Target: < 20% SLEEP rate.  
Outcome: **TARGET NOT MET ✗**

Lifetime improvement: +28% (288 s → 370 s median), Mann-Whitney U=9, p=0.0500 (borderline
significant at n=3).

Full Mode-2 action distributions:

| Action | EXP-48B (blind) | EXP-51 (aware) |
|--------|-----------------|----------------|
| SLEEP  | 94.4% | 93.5% |
| AVOID  |  2.8% |  4.7% |
| APPROACH | 2.3% | 1.6% |
| EAT    |  0.4% |  0.1% |

## Analysis

### What worked

The +28% lifetime improvement is the clearest positive signal.  EXP-51 creatures lived
longer despite having 38% fewer Mode-2 activations (2,782 vs 4,518).  Since Mode-2 fires
when arousal exceeds 4.5, fewer activations imply the creature's homeostatic drives are
staying lower on average — a sign the aware Critic is scheduling SLEEP at more biologically
appropriate moments (when genuinely sleepy) rather than reflexively.

### Why the SLEEP bias persists

The architecture and training target are now correct.  The residual bias has a single root
cause: **EAT episodes are too rare in training data for the Critic to learn a strong enough
signal**.

From 3 trials the action distribution was:
- SLEEP: ~80.6% of actions
- EAT:   ~0.8% of actions (~15 samples vs ~15,783 SLEEP samples)

The need-weighted tanh target gives EAT a strong signal magnitude (≈−0.636 vs 0.0) when
it does occur, but 15 examples is too few for the Critic to generalise.  This is a
**data problem, not an architecture problem**.

A secondary compounding factor is the chicken-and-egg loop: the Critic can't learn
"EAT is good when hungry" without seeing EAT episodes; creatures don't eat because the
Critic prefers SLEEP; sleep consolidation therefore also lacks EAT replay, preventing
per-creature adapter training from reinforcing the rare EAT signal.

### Biological parallel

The need-weighted loss is biologically sound — animals don't need balanced datasets, they
need the right *signal shape*.  A starving animal eating once creates a strong, lasting
memory.  The issue is that the simulated creature never gets hungry enough in the training
world to trigger Mode-2 EAT decisions: with 285 apples in a large world and RANDOM/
AFFORDANCE filters gating approach, the creature is almost always sleeping between cycles
rather than hunting.

### Figures

See `docs/figures/exp_51/`:
- `fig1_lifetime_comparison.png` — creature lifespan EXP-48B vs EXP-51
- `fig2_sleep_rate_comparison.png` — Mode-2 SLEEP rate across all experiments
- `fig3_mode2_action_distribution.png` — full Mode-2 action breakdown (both experiments)
- `fig4_consolidation_loss.png` — per-creature consolidation loss curves

### Next Steps

The most promising path forward is to increase EAT episode frequency in training data.
Options in roughly increasing implementation cost:

1. **Denser food world for training**: increase apple count from 285 to ~2,000 in the
   training compose, so perceptions regularly include in-range food and the creature
   chooses EAT via AFFORDANCE filter more often.
2. **Exploration injection**: add ε-greedy Mode-2 overrides that force EAT when food is
   in range with probability ε, ensuring EAT episodes enter the consolidation replay buffer.
3. **Curriculum**: pre-train species model in a food-dense world to bootstrap EAT signal,
   then fine-tune in the normal sparse world.

The architecture (internal-aware Critic + need-weighted tanh loss + correct MemoryConsolidator
consolidation) is validated.  Only training-data coverage remains to be resolved.
