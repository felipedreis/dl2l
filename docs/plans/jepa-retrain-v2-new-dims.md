# JEPA Retrain v2: New State Dimensions + UnifiedPredictor

**Parent context:** Experiment `20260709_memory_vs_wm_v1` showed the `internal_critic` model failing
with 97.5% AVOID selection due to domain shift — it was trained on p9 data that did not include
orexin/endocrine/neuromodulation subsystems or CACTUS/ALOE objects. This plan retrains the model on
new-stack data with expanded internal-state dimensions and merges Predictor+Critic into a single
two-head `UnifiedPredictor`.

---

## 1. Scope

| Area | Change |
|------|--------|
| Simulation configs | 5 creatures (was 3); new data-collection config |
| Docker composes | Split `collisionDetector` from `holder`; update all 5 existing + new DC |
| `ml/jepa/model.py` | Add `UnifiedPredictor`, `InternalCriticUnifiedModel` |
| `ml/scripts/prepare_dataset.py` | Read Parquet; add nm + endocrine dims to h_t |
| `ml/jepa/train.py` | Handle `InternalCriticUnifiedModel` (single forward → two outputs) |
| `ml/jepa/export.py` | Export unified predictor as NDList-returning TorchScript |
| `ml/scripts/train_species.py` | Add `unified_critic` variant |
| Java `MLServiceExtension` | Add `unifiedPredictor` field to `LoadedModels` |
| Java `WorldModelEngine` | Add unified-predictor inference path |
| Java `MemoryConsolidator` | Add unified-predictor training path |
| Java `FullAppraisal` | Extend `encodeInternalState()` for nm + endocrine prefixes |
| Data collection | 10-trial run script; new simulation + docker compose configs |

---

## 2. New Internal-State Feature Vector (h_t)

Current: `[ht_hunger, ht_sleep, ht_pain, ht_tedium]` (4 dims, `LIVE_EMOTION_INDICES=[0,1,4,5]`)

New: `[ht_hunger, ht_sleep, ht_pain, ht_tedium, nm_dopamine, nm_serotonin, nm_orexin, end_cortisol_tonic]` (8 dims)

Sources in Parquet:
- `drives.parquet`: `init_hunger`, `init_sleep`, `init_pain`, `init_tedium` (from `emotional_state` snapshot at action time — directly joinable by `creature_key + time`)
- `neuromodulators.parquet`: `dopamine`, `serotonin`, `orexin` — indexed by `seq` (per-creature monotonic counter)
- `endocrine.parquet`: `cortisol_tonic` — indexed by `seq`

Join strategy for neuromodulators/endocrine: both use `seq` (not simulation time). Use normalised
fraction `t_frac = seq / max_seq` per creature, and action `t_frac = rank / max_rank` per creature;
apply `pd.merge_asof` on `t_frac` (nearest forward-fill). This is sound because neuromodulator/cortisol
tonic values change slowly (leaky integrators), so fractional-time approximation introduces negligible noise.

`stats.json` `internal_state_feature_order`:
```json
["ht_hunger", "ht_sleep", "ht_pain", "ht_tedium",
 "nm_dopamine", "nm_serotonin", "nm_orexin", "end_cortisol_tonic"]
```

---

## 3. UnifiedPredictor Architecture

Merges the separate `Predictor` and `Critic` models into one two-head network. Preserves the
`internal_critic` property: world dynamics are world-only; emotion prediction uses both.

```
z_world_adapted (latent_dim)  ──┬──> world_trunk ──> z_next (latent_dim)  ─────────────┐
a_t (action_dim)              ──┘                                                      │
                                                  concat(z_next, z_internal) ──> emotion_head ──> Δh (emotion_dim, tanh)
z_internal (internal_dim)  ──────────────────────────────────────────────────────────┘
```

```python
class UnifiedPredictor(nn.Module):
    # world_trunk: MLP(latent_dim + action_dim → hidden_dim → latent_dim)
    # emotion_head: MLP(latent_dim + internal_dim → hidden_dim → emotion_dim, tanh)
    def forward(self, z_world, a, z_internal) -> tuple[Tensor, Tensor]:
        z_next = self.world_trunk(cat([z_world, a]))
        emotion = tanh(self.emotion_head(cat([z_next, z_internal])))
        return z_next, emotion
```

TorchScript export: wrap in a `UnifiedPredictorWrapper(NDList) → NDList` that accepts
`[z_world, a, z_internal]` and returns `[z_next, emotion]`. Java uses `get(0)` / `get(1)`.

New model variant: `InternalCriticUnifiedModel` (extends `InternalCriticModel`'s encoder/internal-encoder
pattern, replaces separate predictor+critic with `UnifiedPredictor`).

### Model file count

| File | Role |
|------|------|
| `species_encoder.pt` | World encoder (unchanged) |
| `species_internal_encoder.pt` | Internal encoder (unchanged) |
| `species_adapter.pt` | Per-creature adapter (unchanged) |
| `species_unified_predictor.pt` | New: replaces predictor + critic |

4 files instead of 5.

---

## 4. Java Changes

### 4.1 `MLServiceExtension.LoadedModels`

Add `unifiedPredictor` as an Optional. If `contract.hasUnifiedPredictor` is true, load
`species_unified_predictor.pt` and leave `predictor` / `critic` null.

```java
public record LoadedModels(
    ZooModel<NDList, NDList> encoder,
    ZooModel<NDList, NDList> predictor,   // null when hasUnifiedPredictor
    ZooModel<NDList, NDList> critic,       // null when hasUnifiedPredictor
    ZooModel<NDList, NDList> unifiedPredictor, // null when !hasUnifiedPredictor
    ZooModel<NDList, NDList> internalEncoder,
    ModelContract contract
)
```

Add `MODEL_FILES_UNIFIED` constant. Add `hasUnifiedPredictor` boolean to `ModelContract`.

### 4.2 `WorldModelEngine`

Add fast path for unified predictor:

```java
if (contract.hasUnifiedPredictor) {
    // 1. z_world = encoder.predict([s_t])
    // 2. z_adapted = adapter.predict([z_world])
    // 3. z_internal = internalEncoder.predict([h_t])
    // 4. result = unifiedPredictor.predict([z_adapted, a_encoded, z_internal])
    //    → NDList: result.get(0)=z_next, result.get(1)=emotion_scores
    // return buildActionCosts(result.get(1))
}
```

Remove strategy routing for unified path (all routing is internal to the model).

### 4.3 `MemoryConsolidator`

Add `unifiedPredictorModel` + `unifiedPredictorTrainer` fields (parallel to existing predictor/critic).
When `contract.hasUnifiedPredictor`:
- Load trainable copy of `species_unified_predictor`
- In `trainBatch()`: forward(z_adapted, a, z_internal) → (z_next, emotion); compute
  `loss = alpha * L_pred + beta * L_emo`; backward on unified predictor only (adapter grads still flow)
- In `postStop()`: close unified predictor trainer + model

### 4.4 `FullAppraisal.encodeInternalState()`

Extend the feature-name dispatch to handle three prefixes:

```java
for (int i = 0; i < featureOrder.size(); i++) {
    String name = featureOrder.get(i);
    if (name.startsWith("ht_")) {
        state[i] = (float) creature.emotions().getLevel(name.substring(3));
    } else if (name.startsWith("nm_")) {
        state[i] = switch (name.substring(3)) {
            case "dopamine"  -> (float) daTonic;
            case "serotonin" -> (float) serotoninTonic;
            case "orexin"    -> (float) orexinTonic;
            default          -> 0f;
        };
    } else if (name.startsWith("end_")) {
        state[i] = switch (name.substring(4)) {
            case "cortisol_tonic" -> (float) cortisolTonic;
            default               -> 0f;
        };
    }
}
```

Add `cortisolTonic` field; populate in `onEndocrineState(EndocrineState es)`.

### 4.5 `ModelContract`

Add `hasUnifiedPredictor: boolean` field (false for all legacy contracts).

---

## 5. Python ML Changes

### 5.1 `prepare_dataset.py` — Full Rewrite

Input: Parquet files in `{data_dir}/{condition}/trial_{N}/`
Tables used: `actions.parquet`, `drives.parquet`, `perceptions.parquet`,
             `neuromodulators.parquet`, `endocrine.parquet`

Pipeline:
1. Glob all `actions.parquet` files (walk data_dir tree)
2. For each file, load actions + drives + perceptions by (creature_key, trial)
3. Join drives → actions on (creature_key, time) — direct key join
4. Join perceptions → actions on (creature_key, time) — take last perception before action time
5. Build `s_t`: [distance, angle, sin(angle), OHE(object_type)] (same as before)
6. Load neuromodulators.parquet + endocrine.parquet; normalize seq → t_frac per creature
7. Join nm/endocrine to actions via `merge_asof` on t_frac
8. Build `h_t`: [hunger, sleep, pain, tedium, dopamine, serotonin, orexin, cortisol_tonic]
9. OHE action → `a_t` vector
10. Build target `emotion_target` = `final_{drive} - init_{drive}` for all 4 drives (and 0 for nm dims)
11. Assign train/val split by trial number: trials 1–8 → train, 9–10 → val
12. Write `train.parquet`, `val.parquet`, `stats.json` to output dir

Update `stats.json`:
```json
{
  "perception_feature_order": [...],
  "internal_state_feature_order": ["ht_hunger","ht_sleep","ht_pain","ht_tedium",
                                    "nm_dopamine","nm_serotonin","nm_orexin","end_cortisol_tonic"],
  "action_feature_order": [...],
  "emotion_dim": 4,
  "internal_dim": 8,
  ...
}
```

### 5.2 `model.py` — Add UnifiedPredictor + InternalCriticUnifiedModel

```python
class UnifiedPredictor(nn.Module):
    def __init__(self, latent_dim, action_dim, internal_dim, emotion_dim, hidden_dim):
        ...

class InternalCriticUnifiedModel(nn.Module):
    # encoder, internal_encoder, adapter, unified_predictor
    # forward: z_world=encoder(s); z_int=internal_encoder(h); z_adp=adapter(z_world)
    #          z_next, emotion = unified_predictor(z_adp, a, z_int)
    # Loss inputs: (z_next_pred, z_next_target, emotion_pred, emotion_target)
    ...
```

### 5.3 `train.py` — Handle UnifiedModel

Add `UNIFIED_MODELS = (InternalCriticUnifiedModel,)`.

In `train_one_epoch`: detect unified model, call single forward → (z_next, emotion), compute
`loss = L_pred + lambda_crit * L_emo`. No separate critic forward call needed.

Update `_ACTION_DIM_EFFECTS` to cover the 8-dim h_t (nm dims: dopamine raised by EAT+APPROACH,
orexin lowered by SLEEP, serotonin raised by EAT).

### 5.4 `export.py` — Export UnifiedPredictor

If model is `InternalCriticUnifiedModel`:
- Trace `species_unified_predictor.pt` wrapping `forward(NDList[z_world, a, z_int]) → NDList[z_next, emotion]`
- Skip separate predictor + critic exports
- Write `has_unified_predictor: true` in `model_contract.json`
- `model_variant = "unified_critic"`

### 5.5 `train_species.py` — Add `unified_critic` variant

```python
elif args.variant == "unified_critic":
    model = InternalCriticUnifiedModel(...)
```

---

## 6. Data Collection Config

### 6.1 Simulation config: `simulations/datacollect_v2_random.conf`

- 5 creatures, `reposition=true`, `maxRuntimeMinutes=120`
- `enabledFilters = [RANDOM]` only (no MEMORY, no WORLD_MODEL)
- Full subsystem stack (orexin, endocrine, neuromodulation, expectancy DISCRETE)
- Full world (300 RED_APPLE + 300 GREEN_APPLE + 300 GRAY_APPLE + 50 CACTUS + 100 ALOE)

### 6.2 Docker compose: `docker/docker-compose-datacollect-v2.yml`

Split `collisionDetector` into its own service (per user request):
```yaml
dl2l-detector:
  image: dl2l
  environment:
    ROLE: "collisionDetector"
    HOST: "dl2l-detector"
  depends_on:
    dl2l-manager: { condition: service_healthy }

dl2l-holder:
  image: dl2l
  environment:
    ROLE: "holder"
    HOST: "dl2l-holder"
  ports: ["8080:8080"]
  depends_on:
    dl2l-manager: { condition: service_healthy }
```

> **Note on TypedActor issue (#49):** The original compose files co-located holder+collisionDetector
> to work around a `TypedActor.getActorRefFor()` resolution failure across JVMs. The user explicitly
> requests splitting; verify on first trial run that `CollisionDetectorActor` messages arrive correctly
> at the holder. If the issue persists, the workaround (same JVM) must be reinstated.

Also update `docker-config.conf` if needed: seed nodes should still point to `dl2l-manager:2551`.

### 6.3 Run script: `scripts/run_datacollect_v2.sh`

- 10 trials, single condition (RANDOM only)
- Wait for `dl2l-holder` to exit (holder exits when simulation ends)
- Extract with `exp_extract.py --condition datacollect_v2 --trial N`
- Output: `ml/data_datacollect_v2/`

---

## 7. Update All 5 Existing Experiment Configs

Change `quantity = 3` → `quantity = 5` in:
- `simulations/20260709_memory_vs_wm_v1_1_baseline.conf`
- `simulations/20260709_memory_vs_wm_v1_2_memory_only.conf`
- `simulations/20260709_memory_vs_wm_v1_3_memory_consolidation.conf`
- `simulations/20260709_memory_vs_wm_v1_4_jepa_only.conf`
- `simulations/20260709_memory_vs_wm_v1_5_jepa_consolidation.conf`

Also update all 5 docker composes to split collisionDetector (same pattern as datacollect compose).

---

## 8. Implementation Order

1. **Simulation configs** — update quantity=5 in all 5 existing + create datacollect config
2. **Docker composes** — split all 5 existing + create datacollect DC
3. **Run script** — `scripts/run_datacollect_v2.sh`
4. **Python ML** — prepare_dataset.py, model.py, train.py, export.py, train_species.py
5. **Java** — FullAppraisal (simplest, standalone) → ModelContract → MLServiceExtension → WorldModelEngine → MemoryConsolidator
6. **Build + compile check** — `mvn package`
7. **Start data collection** — 10 trials, background

---

## 9. What This Does NOT Change

- `s_t` perception encoding (same 9 features, same OHE scheme)
- `dataset.py` — should work unchanged if `prepare_dataset.py` writes correct columns
- Action dim / action OHE scheme
- `WorldModelFilter.encodePerception()` — s_t encoding in Java
- `IndividualAdapter` architecture
- `InternalEncoder` architecture
- Database schema (no new tables needed)
- `exp_extract.py` (already extracts neuromodulators + endocrine)
