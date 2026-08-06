# EXP-51 — Internal-Aware Critic & WANDER Training Fix: SLEEP Bias Elimination

## Purpose

EXP-48B established that 94–95% of all Mode-2 (deliberative) action selections are SLEEP,
regardless of whether the creature is hungry or food is available. That experiment showed that
adding a dual-encoder Predictor — which gives the world model access to the creature's
homeostatic state `z_internal` — did not by itself reduce the bias: the Critic was receiving
`z_internal` during inference but had not been trained with it in mind.

This experiment pursues two successive interventions:

1. **Internal-aware Critic (b6)**: train the Critic loss with `concat(z_next, z_internal)` as
   input so it can learn that SLEEP is costly when hunger is high and food is nearby.
2. **WANDER training fix (b8)**: correct a silent data pipeline bug that excluded all WANDER
   transitions from the training set, then re-enable WANDER as a Mode-2 candidate.

## Assumptions

1. The SLEEP bias is caused by the Critic's inability to distinguish high-hunger from
   low-hunger states — confirmed by the EXP-48B null result where a blind Critic (no
   `z_internal`) produced 94.4% SLEEP despite the Predictor having dual-encoder access.
2. Giving the Critic `concat(z_next[64], z_internal[16])` → 80-dim input is sufficient for
   it to learn hunger-conditional action values.
3. WANDER was never included in training data: the dataset builder silently dropped all
   self-targeted actions (WANDER's `target_key` is always the creature itself — no external
   perception record exists — so `find_target_perception()` dropped the row). This produced
   0 WANDER training samples, forcing the Predictor to extrapolate out-of-distribution and
   causing the Critic to assign spuriously favourable scores to WANDER.
4. Adding self-targeted actions with zeroed perception features allows the Predictor to learn
   what the world state looks like after WANDER in a no-food-visible context.

## Hypothesis

`Critic(concat(z_next, z_internal), action)` trained on a dataset that includes WANDER and
self-targeted SLEEP transitions will:
- Learn that SLEEP is costly when hunger is high and food is visible, directing Mode-2 toward
  APPROACH or EAT instead.
- Learn that WANDER is preferable to SLEEP when hungry with no food visible, breaking the
  SLEEP-stationary-starvation feedback loop.
- Reduce Mode-2 SLEEP rate significantly below the 92.8% achieved by the internal-aware
  Critic alone (b6).

## Results

### Model checkpoints

| Checkpoint | Description | Val L_pred |
|---|---|---|
| `exp_blind` | Blind Critic (λ_crit=0), same architecture as b6 | 0.6614 |
| `exp_b6` | Internal-aware Critic, WANDER excluded from training and Mode-2 | 0.7051 |
| `exp_b8` | Internal-aware Critic, WANDER included in training and Mode-2 | **0.6555** |

b8 achieves the best validation predictor loss of any Critic-enabled checkpoint. The
improvement over b6 (0.7051 → 0.6555) reflects the richer training distribution: b8 sees
WANDER and no-food SLEEP transitions that b6 never encountered, giving the Predictor better
coverage of the contexts that actually dominate Mode-2 at inference time.

### Action selection — sparse world (180 apples, 800×600)

| Model | Lifetime (median) | Mode-2/cs | SLEEP% | WANDER% | APPROACH% |
|---|---|---|---|---|---|
| Blind (`exp_blind`) | 210s | 7.97 | 95.7% | 0.0% | 1.4% |
| b6 (aware Critic, no WANDER data) | 207s | 7.52 | 92.8% | 0.0% | 3.9% |
| **b8 (aware Critic + WANDER data)** | **216s** | **4.80** | **60.0%** | **29.2%** | **6.4%** |

### Action selection — dense world (720 apples, 800×600)

| Model | Lifetime (median) | Mode-2/cs | SLEEP% | WANDER% | APPROACH% |
|---|---|---|---|---|---|
| Blind (`exp_blind`) | 222s | 7.87 | 95.4% | 0.0% | 0.6% |
| b6 (aware Critic, no WANDER data) | 212s | 7.77 | 92.0% | 0.0% | 3.7% |
| **b8 (aware Critic + WANDER data)** | **2264s** | **0.48** | **58.9%** | **30.1%** | **5.6%** |

Target: < 20% SLEEP rate in Mode-2. **TARGET NOT MET**, but SLEEP bias reduced by 35–36 ppt
(from 95.7% to ~60%) — the largest reduction achieved across all EXP-51 experiments.

## Analysis

### Intervention 1: internal-aware Critic (b6)

The first intervention trained the Critic with `z_internal` as an additional input, so it
could in principle learn that "SLEEP when very hungry" is a bad strategy. The result was a
modest but consistent improvement: SLEEP rate fell from 95.7% to 92.8% in the sparse world,
and APPROACH increased from 1.4% to 3.9%.

![**Fig. 1** — Median creature lifetime for the blind Critic baseline (EXP-48B) and the
internal-aware Critic (EXP-51/b6) in the sparse world (180 apples, 800×600). Each dot is one
creature. The bars show medians. The negligible lifetime difference (210s vs 207s) shows that
the modest SLEEP bias reduction achieved by b6 alone was not enough to meaningfully change
survival. The Critic was learning the right direction but most Mode-2 events still fired in
no-food-visible contexts where only SLEEP remained as a candidate after WANDER was excluded.
](../figures/exp_51/fig1_lifetime_comparison.png)

The APPROACH improvement (1.4% → 3.9%, Fig. 3) confirms that the Critic is functioning
correctly: when food IS visible at Mode-2 time, it correctly scores APPROACH lower (better)
than SLEEP. The problem is that food-visible Mode-2 events are rare — most Mode-2 triggers
occur when the creature is starving and no food is in perceptual range, leaving SLEEP as the
only scored candidate.

![**Fig. 2** — Mode-2 SLEEP selection rate across experiment generations. EXP-43 and EXP-48B
represent the pre-Critic baselines. EXP-51/b6 is the first internal-aware Critic result.
The gap between b6 (92.8%) and the 20% target reveals that the Critic architecture alone is
insufficient — the training data was missing the contexts that dominate Mode-2 at inference
time (WANDER and no-food-visible SLEEP transitions). The 20% target line is shown as a
reference for what would constitute a genuinely balanced action distribution.
](../figures/exp_51/fig2_sleep_rate_comparison.png)

![**Fig. 3** — Full Mode-2 action distribution for the blind Critic (left) and the
internal-aware b6 Critic (right) in the sparse world. The shift from 95.7% to 92.8% SLEEP
is visible, as is the APPROACH increase from 1.4% to 3.9%. WANDER is absent in both because
it was excluded from Mode-2 scoring after early experiments showed the Predictor
extrapolated its z_next out-of-distribution, causing the Critic to assign it spuriously
favourable (negative) costs that caused WANDER to win 30–36% of Mode-2 events and shorten
creature lifetimes.
](../figures/exp_51/fig3_mode2_action_distribution.png)

Training convergence for both conditions is shown below. Both models learned a stable
Predictor with decreasing MSE loss. The Critic loss (L_crit) in b6 remained elevated
relative to the blind baseline because the Critic is now attempting a harder task: predicting
need-satisfaction targets that depend on the internal state, not just on which action was
taken.

![**Fig. 4** — Per-creature consolidation loss (MSE) over batches for the blind Critic
baseline (left) and the b6 aware Critic (right). The consolidation process runs online inside
the simulation: after each episode the creature's trajectory is batched and the model is
updated. All creatures converge within their lifetimes. The b6 loss is slightly higher than
blind because the Critic adds a harder learning objective (need-satisfaction targets are
conditioned on `z_internal`), but the Predictor component (which drives the main training
signal) converges in both cases.
](../figures/exp_51/fig4_consolidation_loss.png)

### Intervention 2: WANDER training fix (b8)

#### Root cause of the WANDER OOD problem

During dataset construction, `prepare_dataset.py::find_target_perception()` looked up each
action's target object in the perception log and discarded the row if no perception record
was found. WANDER's `target_key` is always the creature itself — creatures never appear as
perceived external objects — so **every WANDER row was silently dropped**. The same happened
to SLEEP events directed at self (i.e., SLEEP chosen when no food was visible), which are
precisely the Mode-2 SLEEP events we were trying to understand.

The training parquet contained 0 WANDER samples and 0 no-food-visible SLEEP samples.
The Predictor had never seen either context, so at Mode-2 time it was forced to extrapolate
out-of-distribution for both.

#### Fix

A one-line change in `find_target_perception()`: when `target_key == creature_key`, append
the row with zeroed perception features `(distance=0, angle=0, direction=0, object_type=None)`
representing an undefined/no-object perception context. This added 1,500 WANDER samples
(7.2% of the training set) and a large number of self-targeted SLEEP samples that mirror the
actual Mode-2 context. WANDER was simultaneously re-enabled in `WorldModelFilter.java` (only
OBSERVE remains excluded, as it genuinely has no world-model signal).

A secondary fix pinned `live_emotion_dims` to the predefined `LIVE_EMOTION_INDICES = [0,1,4,5]`
rather than auto-detecting from variance. Pain and tedium had zero variance in the `train_p7`
dataset (no CACTUS/ALOE encounters), so the auto-detector collapsed to `[0,1]`, silently
breaking compatibility with the 4-dim internal encoder.

#### Effect on Mode-2 action distribution

![**Fig. 6** — Mode-2 action distribution in the sparse world (180 apples) for the blind
Critic (left), b6 aware Critic without WANDER data (centre), and b8 aware Critic with WANDER
data (right). The shift is striking: SLEEP drops from 95.7% to 60.0%, WANDER appears at 29.2%,
and APPROACH climbs to 6.4%. The WANDER share directly displaces SLEEP in no-food-visible
Mode-2 contexts — exactly the scenario where SLEEP was winning by default because the Predictor
had no other well-trained candidate. APPROACH gains reflect food-visible Mode-2 contexts where
the Critic now correctly scores APPROACH below SLEEP.
](../figures/exp_51/fig6_b8_mode2_sparse.png)

#### WANDER as a homeostatic regulator

The key effect of WANDER in Mode-2 is not just a change in action distribution — it is a
change in the creature's metabolic trajectory. When the creature sleeps instead of wandering,
it remains stationary. Hunger continues to accumulate with no chance of food contact. The
next Mode-2 trigger fires with higher arousal, SLEEP wins again, and the spiral continues
until death. When WANDER is selected, the creature moves. In a food-present world, movement
leads to food contact, and the AFFORDANCE filter selects EAT immediately on contact. Hunger
drops, arousal falls below 4.5, and Mode-2 does not fire again for a while.

```
SLEEP in Mode-2 (b6/blind):
  Arousal > 4.5 → Mode-2 → SLEEP → stationary → hunger rises → arousal > 4.5 → ...
  death spiral: Mode-2 fires 7.8/cs, lifetime 212s

WANDER in Mode-2 (b8):
  Arousal > 4.5 → Mode-2 → WANDER → movement → food contact → AFFORDANCE EAT
  → hunger drops → arousal < 4.5 → normal cycle resumes
  homeostasis: Mode-2 fires 0.48/cs, lifetime 2264s
```

![**Fig. 7** — Left: Mode-2 firing rate (events per creature-second) across all six
model × density conditions. Right: corresponding Mode-2 SLEEP selection rate. In the dense
world, b8 reduces Mode-2 firing rate by 94% (from 7.77 to 0.48/cs) — Mode-2 barely triggers
because hunger rarely spikes above 4.5. The sparse world shows a smaller but still clear
reduction (40%). Critically, the SLEEP rate drop (right panel) is consistent across both
densities (~35 ppt), confirming the effect is driven by the Critic's WANDER signal and not
by incidental food contact from higher density alone. The dense-world b8 bar on the left is
nearly invisible at 0.48/cs, illustrating how thoroughly the death spiral is broken when
WANDER is available and correctly trained.
](../figures/exp_51/fig7_b8_mode2_rate_sleep.png)

The lifetime consequence of this shift is shown below across all six model × density
combinations tested.

![**Fig. 5** — Median creature lifetime for all six model × food-density combinations. The
dashed vertical line separates sparse (180 apples) from dense (720 apples) worlds. Within
the sparse world the lifetime difference between conditions is small (210–216s) because even
with WANDER selected in Mode-2, lower food density means movement does not always lead to
immediate food contact. In the dense world the effect is dramatic: b8 reaches 2264s median
lifetime (10× over b6 at 212s). All three creatures in the b8-dense run died at nearly the
same time (~2264s), consistent with the world gradually being depleted through 709 total EAT
events over the run. The blind model in the dense world (222s) shows that a food-rich
environment alone does not solve the problem — the WANDER training fix is the critical
ingredient.
](../figures/exp_51/fig5_b8_lifetime_all.png)

#### Filter stack isolation

Two additional ablations helped isolate the contribution of each filter:

**Dense world with b6 (4× food density, no WANDER fix)**: SLEEP bias reduced by only 3 ppt
(95.4% → 92.0%), virtually identical to the sparse world result. Higher food density does not
reduce SLEEP bias because most Mode-2 events fire in no-food-visible contexts regardless of
world density. The Critic cannot score APPROACH if food is not in the candidate set.

**WMF-only (no AFFORDANCE, no TARGET_DISTANCE)**: removing all other filters revealed that
the Critic's APPROACH signal is actually stronger than the full stack suggests (11.5% APPROACH
in isolation vs 3.7% with AFFORDANCE). AFFORDANCE's fast EAT reflex at food contact pre-empts
many decisions that would otherwise reach Mode-2. However, AFFORDANCE is non-replaceable for
survival: removing it drops lifetime from 222s to 144s even in the dense world. The RANDOM
filter also selects SLEEP 81% of the time, confirming that the bias is in *candidate
generation* (SLEEP is present in nearly every perception context), not solely in Critic
scoring.

### Why 60% SLEEP remains

Even with the WANDER fix, SLEEP wins 60% of Mode-2 events. Two structural reasons account
for this:

1. **Non-hunger arousal**: Mode-2 triggers whenever any arousal dimension exceeds 4.5 — not
   only hunger. When pain, tedium, or stress drive arousal while hunger is low, SLEEP can
   genuinely be the best action (the Critic correctly assigns it low cost for those states).

2. **Single-step credit horizon**: the Critic scores one time-step ahead. WANDER's explicit
   training target is tedium relief, which is small when tedium is low. The Critic cannot
   reason that WANDER → movement → food contact → EAT → hunger relief across multiple steps;
   it only sees the immediate next world state. Encoding that multi-step value requires K-step
   Predictor rollout, which is the motivation for Phase 7 — Hierarchical planning & multi-step
   lookahead (GitHub milestone #8).

## Conclusions

1. **Root cause confirmed**: the SLEEP bias was not an architectural problem — it was a data
   pipeline bug. `find_target_perception()` silently dropped every self-targeted action
   (WANDER and no-food-visible SLEEP), leaving 0 WANDER samples in the training set. The fix
   is a one-line change in the dataset builder.

2. **WANDER is a functional homeostatic regulator**: Mode-2 WANDER breaks the
   stationary-starvation loop and enables the creature to maintain hunger homeostasis through
   exploration-driven food contact. In food-rich environments this produces a 10× lifetime
   improvement and a 94% reduction in Mode-2 firing rate.

3. **The Critic architecture is sound**: the z_internal-aware Critic correctly scores
   APPROACH over SLEEP when food is visible (1.4% → 6.4%) and WANDER over SLEEP when no
   food is visible once given proper training coverage.

4. **Remaining SLEEP bias (60%) requires multi-step planning**: a single-step Critic cannot
   assign hunger-relief value to WANDER because the hunger relief only materialises across
   multiple time steps via food discovery. K-step Predictor rollout (Phase 7) is the natural
   next step.

5. **Secondary dataset fix**: `live_emotion_dims` must be pinned to `LIVE_EMOTION_INDICES`
   in `prepare_dataset.py`, not auto-detected from variance, to maintain compatibility with
   the 4-dim internal encoder when some drives have zero variance in a given training dataset.
