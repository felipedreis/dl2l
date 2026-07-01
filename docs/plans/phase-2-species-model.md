# Phase 2 — Species Model (offline pre-training)

**Epic:** [#4](https://github.com/felipedreis/dl2l/issues/4)  
**Tasks:** [#16](https://github.com/felipedreis/dl2l/issues/16) (2.1 Trajectory export), [#17](https://github.com/felipedreis/dl2l/issues/17) (2.2 JEPA architecture), [#18](https://github.com/felipedreis/dl2l/issues/18) (2.3 Offline training)  
**Branch:** `features/species-model`

---

## Overview

Phase 2 trains the "species" base JEPA world model fully offline on creature trajectory data and exports TorchScript artifacts for later DJL integration (Phase 5). It runs in parallel with Phases 3–4 (memory/trace pipeline). **No Java behaviour is changed** — only new extractors, new named queries on existing entities, and the entirely-offline `/ml` tree.

Three tasks:
1. **2.1** — New Java extractors + Python assembly → `(s_t, a_t, emotion_target)` training dataset
2. **2.2** — PyTorch JEPA architecture: encoder, predictor, critic + individual adapter
3. **2.3** — Offline SIGReg training + collapse check + TorchScript + `model_contract.json` export

---

## Prior decisions carried forward

- **Phase 0 (EXP-P0-1):** Mode-1 reactive policy covers the full distance/angle range for `baseline_1node_1creature.conf`. No random-policy episodes required for that config. A separate probe is needed if `basic.conf` (3 object types) is used.
- **Phase 1 (EXP-P1-1):** Granularity decision is `PER_STIMULUS_FROZEN_BASELINE` (same-drive collision rate 93.3%, far above the 1% threshold). Enum `ReinforcementGranularity.PER_STIMULUS_FROZEN_BASELINE` exists in `creature/common/`. This settles how Δemotion is computed in Phase 4; Phase 2 training data uses the `finalEmotionalState` absolute values (see §2.1.2 below).

---

## Task 2.1 — Trajectory Export

### 2.1.1 Training tuple data model

Each training sample is:

```
s_t     : [distance, angle, direction, type_GRAY_APPLE, type_GREEN_APPLE, type_RED_APPLE]
            dim = 6   (3 continuous + 3 one-hot for object type)

a_t     : [APPROACH, AVOID, EAT, ESCAPE, PLAY, SLEEP, TOUCH, TURN, WANDER]
            dim = 9   one-hot, alphabetical order matching Java enum ordinal

target  : [hunger, sleep, apathy, stress, pain, tedium, fear, curiosity, fertility]
            dim = 9   absolute emotional state, bounded to [0.18, 7.0]
```

- `s_t` = perception features of the **target object** at decision time, sourced from `ObjectSeenState`
  rows whose `objectNumber` matches `ChosenActionState.target`.
- `a_t` = `ActionType` one-hot encoded.
- `target` = `finalEmotionalState` from the `InternalDynamicState` record whose timestamp is the
  first regulation event **after** the action's timestamp, for the same creature. This is the
  absolute arousal level the creature reached after the action's most immediate consequence.

**What this is not**: it is not the full `s_{t+1}` (perceptual next state), and it is not a discounted
sum. It is the emotional-consequence form described in HLD §5 — a direct predictor of the
`HomeostaticRegulation` output associated with an action. This maps to LeCun's critic (state+action
→ future intrinsic cost), not the full predictor.

**Continuous action params not persisted**: `CorticalStimulus.angle/focus/speed` exist at runtime but
are not written to the DB. For Phase 2, `a_t` is discrete-only. If continuous params are needed for
Phase 6 CEM planning, they must be persisted in a future task; note as a known limitation.

**Cognitive-cycle counter not yet present**: Task 3.3 (Phase 3) will add the true per-creature
cognitive-cycle ordinal. For Phase 2, `change_stimulus_state.time` (wall-clock ms) is the ordering
proxy. Tuples are reconstructed by `(creatureKey, time)`, never by row order across tables.

### 2.1.2 Emotion dimension map

From `EmotionalState.java` and `Constants.java` (`MIN_AROUSAL_LEVEL = 0.18`, `MAX_AROUSAL_LEVEL = 7.0`):

| Index | Field | Live today? |
|---|---|---|
| 0 | hunger | **Yes** |
| 1 | sleep | **Yes** |
| 2 | apathy | No (constant 0.0) |
| 3 | stress | No |
| 4 | pain | No |
| 5 | tedium | No |
| 6 | fear | No |
| 7 | curiosity | No |
| 8 | fertility | No |

The critic loss is **masked to live dims only** (detected automatically in the assembly script by
non-zero variance). Non-live dims are predicted but not trained on, so the network does not learn
spurious correlations from constant-zero columns.

This table is the emotion-dimension map shared with `model_contract.json`. It satisfies the
coordination requirement of Task 6.1 for Phase 2's scope.

### 2.1.3 New named queries on existing entities

Add to **`ChosenActionState.java`**:
```java
@NamedNativeQuery(name = "ChosenActionState.getForTrajectory",
    query = "SELECT css.key AS creature_key, css.time AS action_time, " +
            "cas.action AS action_type, cas.actionselectiontype AS selection_type, " +
            "cas.target_key AS target_key " +
            "FROM data.chosen_action_state cas " +
            "JOIN data.change_stimulus_state css ON cas.changestimulusstate_id = css.id " +
            "WHERE css.key = ? ORDER BY css.time")
```

Add to **`InternalDynamicState.java`**:
```java
@NamedNativeQuery(name = "InternalDynamicState.getForTrajectory",
    query = "SELECT css.key AS creature_key, css.time AS regulation_time, " +
            "es_f.hunger_arausal, es_f.sleep_arausal, es_f.apathy_arausal, " +
            "es_f.stress_arausal, es_f.pain_arausal, es_f.tedium_arausal, " +
            "es_f.fear_arausal, es_f.curiosity_arausal, es_f.fertility_arausal " +
            "FROM data.internal_dynamic_state ids " +
            "JOIN data.change_stimulus_state css ON ids.changestimulusstate_id = css.id " +
            "JOIN data.emotional_state es_f ON ids.finalemotionalstate_id = es_f.id " +
            "WHERE css.key = ? ORDER BY css.time")
```

Add to **`ObjectSeenState.java`**:
```java
@NamedNativeQuery(name = "ObjectSeenState.getForTrajectory",
    query = "SELECT css.key AS creature_key, css.time, " +
            "oss.objectnumber_key AS object_key, oss.type AS object_type, " +
            "oss.distance, oss.angle, oss.direction " +
            "FROM data.object_seen_state oss " +
            "JOIN data.change_stimulus_state css ON oss.changestimulusstate_id = css.id " +
            "WHERE css.key = ? ORDER BY css.time")
```

Note: the existing `ObjectSeenState.getPerceptionsByCreature` (used by `PerceptionCoverageExtractor`)
is left unchanged. The new query adds `object_key` and `direction`, which `PerceptionCoverageExtractor`
does not expose.

### 2.1.4 New Java extractors

Three new classes in `analysis/extractor/`, all `CreatureExtractor` subclasses:

**`ChosenActionStateExtractor`**:
- Runs `ChosenActionState.getForTrajectory` with `id.key`
- DataSet columns: `creatureKey`, `action_time`, `action_type`, `selection_type`, `target_key`
- `getName()` → `id + "/trajectory_actions"` → saves as `<id>/trajectory_actions.csv`

**`InternalDynamicStateExtractor`**:
- Runs `InternalDynamicState.getForTrajectory` with `id.key`
- DataSet columns: `creatureKey`, `regulation_time`, `final_hunger`, `final_sleep`, `final_apathy`,
  `final_stress`, `final_pain`, `final_tedium`, `final_fear`, `final_curiosity`, `final_fertility`
- `getName()` → `id + "/trajectory_emotions"` → saves as `<id>/trajectory_emotions.csv`

**`TrajectoryPerceptionExtractor`**:
- Runs `ObjectSeenState.getForTrajectory` with `id.key`
- DataSet columns: `creatureKey`, `time`, `object_key`, `object_type`, `distance`, `angle`, `direction`
- `getName()` → `id + "/trajectory_perceptions"` → saves as `<id>/trajectory_perceptions.csv`
- Deserialise `object_type` bytes via `WorldObjectType` the same way `PerceptionCoverageExtractor` does

Register all three in `RoutineCreator.creatureRoutine` at the end of the existing `new Routine(...)` call.

### 2.1.5 Python assembly script: `ml/scripts/prepare_dataset.py`

Python 3 + pandas + pyarrow. `wd` points at extractor output directory; `out_dir` defaults to `ml/data/`.

```
Steps:
1. glob all *trajectory_actions.csv, *trajectory_emotions.csv, *trajectory_perceptions.csv
2. Concat each group; sort by (creatureKey, time)
3. Filter actions: keep only AFFORDANCE and RANDOM selections (MEMORY is dead)
4. Per creatureKey:
   a. For each action (action_time, target_key):
      - Find last trajectory_perceptions row where object_key == target_key AND time <= action_time
        (nearest prior perception of the target object)
      - If none found: skip this action (target not yet seen)
   b. For each action (action_time):
      - Find first trajectory_emotions row where regulation_time > action_time
        (next regulation event after the action)
      - If none found: skip this action (no subsequent consequence within the trajectory)
5. Assemble tuples: one row per (creatureKey, action_time, s_t_features, a_t_one_hot, emotion_target)
6. Feature engineering:
   - distance, angle, direction: standardise (z-score) using training-set mean/std
   - object_type: one-hot encode to 3 dims (GRAY_APPLE, GREEN_APPLE, RED_APPLE — alphabetical)
   - action_type: one-hot encode to 9 dims (alphabetical ActionType order)
   - emotion_target: raw float32, already in [0.18, 7.0]
7. Compute per-dim variance of emotion_target; mark dims with var > 1e-6 as live
8. Train/val split: 80/20, stratified by creatureKey (no creature straddles both splits)
9. Save:
   - ml/data/train.parquet
   - ml/data/val.parquet
   - ml/data/stats.json: {feature_means, feature_stds, live_emotion_dims, input_dim,
                          action_dim, emotion_dim, min_arousal, max_arousal,
                          action_index_order, emotion_index_order, perception_feature_order}
```

### 2.1.6 Acceptance (Task 2.1)

- `mvn package` compiles clean.
- A baseline run populates `trajectory_actions.csv`, `trajectory_emotions.csv`,
  `trajectory_perceptions.csv` per creature.
- `prepare_dataset.py` produces `train.parquet`, `val.parquet`, `stats.json`.
- `stats.json` shows `live_emotion_dims: [0, 1]`.
- Validation split is creature-disjoint from training split.

---

## Task 2.2 — PyTorch JEPA Architecture

Lives entirely in `/ml`. No Java changes.

### 2.2.1 Directory structure

```
ml/
  requirements.txt          # torch, numpy, pandas, pyarrow, scikit-learn
  jepa/
    __init__.py
    model.py                # Encoder, Predictor, Critic, IndividualAdapter, SpeciesModel
    dataset.py              # TrajectoryDataset (torch.utils.data.Dataset)
    train.py                # SIGReg training loop (called by train_species.py)
    evaluate.py             # Collapse check: per-dim variance + effective rank
    export.py               # TorchScript trace + model_contract.json generation
  scripts/
    prepare_dataset.py      # Task 2.1 assembly (see §2.1.5)
    train_species.py        # Entry point: trains the species base model
    check_collapse.py       # Entry point: validates no representation collapse
    export_model.py         # Entry point: exports TorchScript + contract
  data/                     # .gitignored — raw CSVs + processed parquet
  checkpoints/              # .gitignored — training checkpoints
```

Add to root `.gitignore`: `ml/data/`, `ml/checkpoints/`.

Add `src/main/resources/models/.gitkeep` so the target directory is tracked.

### 2.2.2 Dimensions (from `stats.json`)

| Symbol | Value | Source |
|---|---|---|
| `input_dim` | 6 | distance + angle + direction + 3 one-hot type dims |
| `action_dim` | 9 | ActionType enum cardinality |
| `latent_dim` | 64 | design choice (hidden_dim ≈ 128 per roadmap) |
| `emotion_dim` | 9 | EmotionalState fields |
| `live_dims` | [0, 1] | detected from data variance |
| `MIN_AROUSAL` | 0.18 | `Constants.MIN_AROUSAL_LEVEL` |
| `MAX_AROUSAL` | 7.0 | `Constants.MAX_AROUSAL_LEVEL` |

### 2.2.3 `ml/jepa/model.py`

```python
class Encoder(nn.Module):
    """Enc(s_t) → z_t  [latent_dim]"""
    # MLP: Linear(input_dim, 128) → LayerNorm → ReLU →
    #       Linear(128, 128)      → LayerNorm → ReLU →
    #       Linear(128, latent_dim)

class Predictor(nn.Module):
    """Pred(z_t, a_t) → z_{t+1}_hat  [latent_dim]"""
    # MLP: Linear(latent_dim + action_dim, 128) → LayerNorm → ReLU →
    #       Linear(128, 128)                     → LayerNorm → ReLU →
    #       Linear(128, latent_dim)

class Critic(nn.Module):
    """Crit(z_t, a_t) → emotion_pred  [emotion_dim], bounded to [MIN_AROUSAL, MAX_AROUSAL]"""
    # MLP: Linear(latent_dim + action_dim, 128) → ReLU →
    #       Linear(128, 128)                     → ReLU →
    #       Linear(128, emotion_dim)
    # Final: MIN_AROUSAL + (MAX_AROUSAL - MIN_AROUSAL) * sigmoid(raw)
    # NOT optional: Task 6.3 scores candidates via exp(-cost); unbounded output breaks this.

class IndividualAdapter(nn.Module):
    """Additive delta on Predictor output. One instance per creature, updated at sleep."""
    # Small MLP: Linear(latent_dim, 32) → ReLU → Linear(32, latent_dim)
    # Usage: z_next_adapted = predictor(z, a) + adapter(predictor(z, a))
    # Species base (Encoder + Predictor + Critic) is frozen; adapter is the only trainable part.

class SpeciesModel(nn.Module):
    """Combines the three base modules for training and export."""
    def forward(self, s_t: Tensor, a_t: Tensor) -> tuple[Tensor, Tensor, Tensor]:
        z_t     = self.encoder(s_t)
        z_a     = torch.cat([z_t, a_t], dim=-1)
        z_next  = self.predictor(z_a)
        emotion = self.critic(z_a)
        return z_t, z_next, emotion   # shapes: [B,64], [B,64], [B,9]
```

### 2.2.4 `ml/jepa/dataset.py`

`TrajectoryDataset(torch.utils.data.Dataset)`:
- Reads `train.parquet` or `val.parquet`
- Loads `stats.json` for normalisation means/stds (applied to continuous dims only; one-hot dims are left as-is)
- `__getitem__` returns `(s_t, a_t, emotion_target)` as `float32` tensors

### 2.2.5 Acceptance (Task 2.2)

- `python -c "from jepa.model import SpeciesModel; m = SpeciesModel(6,9,64,9); ..."` runs without error.
- Forward pass shapes: `z_t` → `[B, 64]`, `z_next` → `[B, 64]`, `emotion` → `[B, 9]`.
- `emotion.min() >= 0.18` and `emotion.max() <= 7.0` for random inputs (tested in a unit test or `check_shapes.py`).
- `IndividualAdapter` output shape `[B, 64]` (same as latent_dim).

---

## Task 2.3 — Offline Base Training (SIGReg) + Collapse Check + Export

### 2.3.1 Training objective (LeWorldModel two-term)

```
L = L_pred + λ_sigreg * L_sigreg + λ_crit * L_crit
```

**L_pred** — next-latent prediction:
```
z_t      = Enc(s_t)
z_next   = Pred(z_t, a_t)
z_target = Enc(s_{t+1}).detach()          # stop-gradient on target encoder (BYOL-style)
L_pred   = MSE(z_next, z_target)
```
`s_{t+1}` is the next row in the per-creature time-sorted trajectory. Samples where `s_{t+1}` is
unavailable (last action per creature) are skipped for this term.

**L_sigreg** — SIGReg Gaussian regulariser (prevents representation collapse):
```
mu  = z.mean(dim=0)                       # per-dim batch mean
var = z.var(dim=0)                        # per-dim batch variance
L_sigreg = mu.pow(2).mean() + (var - 1).pow(2).mean()
```
This pushes the batch latent distribution toward N(0, I) without a learned prior, matching
the LeWorldModel formulation. Default `λ_sigreg = 0.1`.

**L_crit** — critic supervised loss (live dims only):
```
emotion_pred = Crit(z_t, a_t)
L_crit = MSE(emotion_pred[:, live_dims], emotion_target[:, live_dims])
```
`live_dims` is loaded from `stats.json`. Default `λ_crit = 1.0`.

### 2.3.2 `ml/scripts/train_species.py`

```
CLI: python train_species.py --data ml/data --epochs 100 --batch 256 --lr 1e-3
                              --lambda-sigreg 0.1 --lambda-crit 1.0 --out ml/checkpoints

- Adam optimiser
- Epoch loop: compute train L, val L; log breakdown (L_pred, L_sigreg, L_crit)
- Save best checkpoint (lowest val L_pred) to ml/checkpoints/best.pt
- Save final checkpoint to ml/checkpoints/final.pt
- Print per-epoch summary to stdout
```

### 2.3.3 `ml/scripts/check_collapse.py`

Run on the validation set after training:

```
1. Load best.pt; set model to eval mode
2. Encode all val s_t → Z  (shape [N, 64])
3. Per-dim variance:  var_i = Z[:, i].var()
   FAIL if any var_i < 1e-4
4. Effective rank:
   sigma = svd(Z - Z.mean(0), compute_uv=False)
   p = sigma / sigma.sum()
   eff_rank = exp(-(p * log(p + 1e-12)).sum())
   FAIL if eff_rank < 0.1 * latent_dim  (i.e. < 6.4)
5. Print per-dim variance table
6. Print: eff_rank, threshold, PASS/FAIL
7. Exit code 0 if all pass, exit code 1 if any fail
```

Exit code 1 is a **failing acceptance condition** (per issue #18): a collapsed model passes shape
tests but is useless; this makes collapse a CI-catchable failure.

### 2.3.4 `ml/scripts/export_model.py`

```
Load best.pt checkpoint
Trace each module with representative dummy tensors:
  species_encoder.pt   ← torch.jit.trace(encoder,   torch.zeros(1, 6))
  species_predictor.pt ← torch.jit.trace(predictor, torch.zeros(1, 64+9))
  species_critic.pt    ← torch.jit.trace(critic,    torch.zeros(1, 64+9))
  species_adapter.pt   ← torch.jit.trace(adapter,   torch.zeros(1, 64))
                         (untrained template; per-creature weights loaded by DJL at runtime)

Write model_contract.json to src/main/resources/models/:
{
  "schema_version": 1,
  "input_dim": 6,
  "latent_dim": 64,
  "action_dim": 9,
  "emotion_dim": 9,
  "live_emotion_dims": [0, 1],
  "emotion_index_order": ["hunger","sleep","apathy","stress","pain","tedium",
                          "fear","curiosity","fertility"],
  "action_index_order":  ["APPROACH","AVOID","EAT","ESCAPE","PLAY",
                          "SLEEP","TOUCH","TURN","WANDER"],
  "perception_feature_order": ["distance","angle","direction",
                               "type_GRAY_APPLE","type_GREEN_APPLE","type_RED_APPLE"],
  "min_arousal": 0.18,
  "max_arousal": 7.0,
  "model_hash": "<sha256 of concatenated bytes of all .pt files>",
  "trained_on": "<ISO-8601 date>"
}
```

`model_hash` is sha256 over the concatenated bytes of all four `.pt` files in alphabetical name order.
The Java loader (Phase 5, Task 5.1) asserts this hash at startup; mismatch aborts loudly.

### 2.3.5 Acceptance (Task 2.3)

- `check_collapse.py` exits 0 on the validation split.
- `src/main/resources/models/` contains `species_encoder.pt`, `species_predictor.pt`,
  `species_critic.pt`, `species_adapter.pt`, `model_contract.json`.
- Variance report: all 64 latent dims have `var > 1e-4`.
- Effective rank `>= 6.4` (10% of `latent_dim = 64`).

---

## Files changed / created

| File | Action |
|---|---|
| `src/.../creature/bd/ChosenActionState.java` | Add `ChosenActionState.getForTrajectory` named query |
| `src/.../creature/bd/InternalDynamicState.java` | Add `InternalDynamicState.getForTrajectory` named query |
| `src/.../creature/bd/ObjectSeenState.java` | Add `ObjectSeenState.getForTrajectory` named query |
| `src/.../analysis/extractor/ChosenActionStateExtractor.java` | Create |
| `src/.../analysis/extractor/InternalDynamicStateExtractor.java` | Create |
| `src/.../analysis/extractor/TrajectoryPerceptionExtractor.java` | Create |
| `src/.../analysis/RoutineCreator.java` | Add 3 new extractors to `creatureRoutine` |
| `ml/requirements.txt` | Create |
| `ml/jepa/__init__.py` | Create |
| `ml/jepa/model.py` | Create |
| `ml/jepa/dataset.py` | Create |
| `ml/jepa/train.py` | Create |
| `ml/jepa/evaluate.py` | Create |
| `ml/jepa/export.py` | Create |
| `ml/scripts/prepare_dataset.py` | Create |
| `ml/scripts/train_species.py` | Create |
| `ml/scripts/check_collapse.py` | Create |
| `ml/scripts/export_model.py` | Create |
| `src/main/resources/models/.gitkeep` | Create |
| `.gitignore` | Add `ml/data/` and `ml/checkpoints/` |

---

## Sequencing within Phase 2

1. Implement Task 2.1 Java extractors and named queries → `mvn package` → run baseline simulation → verify CSVs.
2. Run `prepare_dataset.py` → confirm `stats.json` shows `live_emotion_dims: [0, 1]` and tuple counts are non-trivial.
3. Implement Task 2.2 architecture → verify forward-pass shapes and critic bounds (step 2 and 3 can overlap with step 1 once data contract is agreed).
4. Run Task 2.3 training → `check_collapse.py` → `export_model.py`.
5. Verify all acceptance criteria above before closing Epic #4.

---

## Phase 2 Completion Record (EXP-p9, 2026-07)

The following documents all decisions and changes made after the initial plan during the p9 experiment series.

### Architecture evolution — four model variants

The original plan described a single `SpeciesModel`. During the p9 experiments, three additional architectures were designed and trained to ablate the role of internal homeostatic state (`h_t`) in world-model prediction:

| Variant | Predictor input | Critic input | Python class |
|---|---|---|---|
| `single` | `z_world` | `z_next` | `SpeciesModel` |
| `dual` | `concat(z_world, z_internal)` | `concat(z_next, z_internal)` | `DualSpeciesModel` |
| `internal_critic` | `z_world` | `concat(z_next, z_internal)` | `InternalCriticModel` |
| `internal_predictor` | `concat(z_world, z_internal)` | `z_next` | `InternalPredictorModel` |

All four share the same `Encoder`, `Predictor`, `Critic`, `InternalEncoder`, and `IndividualAdapter` building blocks from `ml/jepa/model.py`. The `DUAL_ENCODER_MODELS` tuple in `train.py` collects all variants that require `h_t` as a third input.

### p9 dataset — trial-based train/val split

Simulation run `p9` used 10 creatures × 10 trials. Prior runs used a creature-based split which caused contamination (the same creature IDs are reused across trials). The fix:

- Added `trial_id` (extracted from the directory name `trial_N/`) to every row in `prepare_dataset.py`.
- `merge_asof` joins now include `trial_id` in the `by` groups to prevent cross-trial emotion/perception matches.
- Trials 1–8 → **train** (359,782 samples); trials 9–10 → **val** (89,731 samples).

`stats.json` dimensions: `input_dim=9`, `action_dim=9`, `emotion_dim=9`, `latent_dim=64`, `internal_latent_dim=16`, `internal_state_dim=4`.

### p9 training results

All four variants trained for 50 epochs on MPS (Apple GPU). Best val L_pred (lower is better):

| Variant | Best val L_pred | Best epoch |
|---|---|---|
| `internal_critic` | **0.1683** | 2 |
| `internal_predictor` | 0.1750 | 2 |
| `single` | 0.1732 | 3 |
| `dual` | 0.1884 | 2 |

All models converge at epoch 2–4, consistent with limited creature diversity (10 unique IDs). The Baum & Haussler 10× rule requires ~900K samples for reliable generalisation; 360K samples puts the model in the compute-optimal zone but below the generalization threshold. The real bottleneck is diversity: more creatures (50–100) with varied conditions, not longer trials.

### Java Strategy Pattern for model variant routing

A Strategy Pattern isolates tensor-routing per variant in both `WorldModelEngine` (inference) and `MemoryConsolidator` (sleep training):

- `ModelVariantStrategy` interface — `buildPredictorInput`, `buildCriticInput`, `requiresInternalState`, `variantName`
- Implementations: `SingleEncoderStrategy`, `DualPredictorStrategy`, `InternalCriticStrategy`, `InternalPredictorStrategy`
- `ModelVariantStrategyFactory.forContract(ModelContract)` — reads `model_variant` from `model_contract.json`; legacy contracts (`has_internal_encoder=true`, no `model_variant`) map to `DualPredictorStrategy`

`model_contract.json` gained a `model_variant` field (`"single"`, `"dual"`, `"internal_critic"`, `"internal_predictor"`).

### export.py fix for per-variant I/O dimensions

The original `export.py` used the `dual` flag (has internal encoder) to set both predictor and critic input sizes. This was wrong for `internal_predictor` (predictor is combined but critic is world-only) and `internal_critic` (predictor is world-only but critic is combined). Fixed:

```python
predictor_latent_in = (latent_dim + internal_latent_dim) if model_variant in ("dual", "internal_predictor") else latent_dim
critic_latent_in    = (latent_dim + internal_latent_dim) if model_variant in ("dual", "internal_critic")    else latent_dim
```

### pg_extract.py — full Python/SQL port of all Java extractors

`scripts/pg_extract.py` was extended to cover every Java extractor that previously required the fat JAR. All queries run via `docker exec psql`. Files written:

**Ensemble (root output dir):**
- `lifetimes.csv` — creature lifetimes in seconds
- `distances.csv` — total traveled distance per creature (sum of `body_state.speed`)
- `perception_coverage.csv` — all object perceptions (creature_key, object_type, distance, angle, time)

**Per-creature (`{key}:{seq}/`):**
- `trajectory_emotions.csv`, `trajectory_actions.csv`, `trajectory_perceptions.csv` — unchanged
- `sleep_episodes.csv`, `reg_hist.csv` — unchanged
- `arousal_history.csv` — 0.001-min binned hunger+sleep arousal averages
- `accumulated_choices.csv` — EAT/PLAY/TOUCH counts accumulated over time by selection type (AFFORDANCE/MEMORY/RANDOM)
- `behavioural_efficiency.csv` — simple/complex task efficiency per 0.001-min bin
- `eaten_nutrients.csv` — accumulated EAT interactions per fruit type over time
- `choices_over_time.csv` — per-event EAT/PLAY/TOUCH log with elapsed time and selection type
- `engrams.csv` — operant conditioning engram records (lay_cycle, reinforced_cycle, eligibility, emotion_delta)
- `consolidation_batches.csv` — per-batch sleep consolidation training stats (onset_cycle, batch_index, loss)

### HuggingFace repositories

| Repo | Type | Contents |
|---|---|---|
| `felipedreis/dl2l-jepa` | Model | TorchScript `.pt` + `model_contract.json` for all 4 variants |
| `felipedreis/dl2l-experiments` | Dataset | `p9/` — parquet files + `stats.json` for p9 experiment |

Upload script: `ml/scripts/upload_hf.py --repo felipedreis/dl2l-jepa --data-repo felipedreis/dl2l-experiments --ckpt checkpoints_p9 --data data_p9 --data-prefix p9`

Models are exported to `src/main/resources/models/{variant}/` for fat-JAR inclusion. The Java loader selects the variant from `model_contract.json`'s `model_variant` field at startup.

### Additional files changed in p9 series

| File | Change |
|---|---|
| `ml/jepa/model.py` | Added `InternalCriticModel`, `InternalPredictorModel` |
| `ml/jepa/train.py` | Added `InternalPredictorModel` to `DUAL_ENCODER_MODELS`; updated imports |
| `ml/jepa/export.py` | Fixed per-variant predictor/critic input dims; added `model_variant` to contract |
| `ml/scripts/prepare_dataset.py` | Trial-based split; `trial_id` in merge_asof `by` groups |
| `ml/scripts/train_species.py` | Added `internal_predictor` variant |
| `ml/scripts/export_model.py` | Added `internal_predictor` variant |
| `ml/scripts/check_collapse.py` | Added `internal_predictor` variant |
| `ml/scripts/upload_hf.py` | Added 4th variant; split `--data-repo` from `--repo`; dataset prefix |
| `scripts/pg_extract.py` | Full port of all Java extractors to Python+SQL |
| `src/.../ml/InternalCriticStrategy.java` | New strategy for `internal_critic` variant |
| `src/.../ml/InternalPredictorStrategy.java` | New strategy for `internal_predictor` variant |
| `src/.../ml/DualPredictorStrategy.java` | New strategy for `dual` variant |
| `src/.../ml/SingleEncoderStrategy.java` | New strategy for `single` variant |
| `src/.../ml/ModelVariantStrategy.java` | New interface |
| `src/.../ml/ModelVariantStrategyFactory.java` | Factory with legacy compat |
| `src/.../ml/ModelContract.java` | Added `model_variant` field |
| `src/.../ml/WorldModelEngine.java` | Delegates tensor routing to strategy |
| `src/.../ml/MemoryConsolidator.java` | Delegates tensor routing to strategy |
| `src/test/.../ml/ModelVariantStrategyTest.java` | 16 unit tests covering all 4 strategies + factory |
