# Plan: Fix Species Critic SLEEP bias — Mode-2 starvation spiral (#48)

**Issue**: https://github.com/felipedreis/dl2l/issues/48
**Date**: 2026-06-29
**Prerequisites**: Issue #43 merged (adapter zero-init fix).

---

## Problem summary

`WorldModelFilter` (Mode-2) selects SLEEP in ~94 % of decisions whenever arousal > 4.5,
regardless of hunger. Root cause: the species Critic was trained without the creature's
internal state in its input, so it cannot learn that SLEEP-while-hungry is costly.
The zero-init fix (#43) exposed this directly — adapter outputs zero at boot, making
Mode-2 consult the species Critic alone.

---

## Experimental design

Two experiments, one shared training dataset.

### Common prerequisite — full-world training simulation

Collect fresh Mode-1 trajectories (no Mode-2, no SLEEP bias contamination) using
the **full world**: all fruit types (RED/GREEN/GRAY/ROTTEN apple) and plant types
(CACTUS, ALOE). More object variety → richer training signal for the Critic.

Config: `simulations/train_p7_retrain.conf` — 10 creatures, 2 holders, all object
types, `enabledFilters = [TARGET_DISTANCE, AFFORDANCE, RANDOM]` (no WORLD_MODEL).

Docker: `docker/docker-compose-train-p7.yml`

After the run, extract CSVs:
```bash
java -jar target/l2l-2.0.0-SNAPSHOT-wd.jar \
  --host localhost --port 2551 --roles holder \
  --extractor --save data/train_p7
```

Then prepare dataset:
```bash
cd ml
python -m scripts.prepare_dataset --wd ../data/train_p7 --out data
```

---

### Experiment A — Retrain baseline (same architecture, fresh data)

**Question**: does retraining on richer full-world data alone improve Mode-2 behaviour
vs EXP-43 (which used a model trained 2026-06-22 on 2-apple data)?

Architecture: identical to today — single Encoder(perception → z[64]), Predictor,
Critic, IndividualAdapter. No Java changes required.

```bash
cd ml
python -m scripts.train_species --data data --ckpt checkpoints/exp_a --epochs 100
python -m scripts.check_collapse --ckpt checkpoints/exp_a
python -m scripts.export_model --ckpt checkpoints/exp_a \
    --out ../src/main/resources/models
mvn package
```

Validation: `docker/docker-compose-exp-p6-1.yml` (same harness as EXP-43:
3 creatures, 1 holder, 90 RED + 90 GREEN apples, Mode-2 threshold 4.5).

---

### Experiment B — Dual encoder (perception + internal state)

**Question**: does adding an InternalEncoder for the creature's homeostatic state
further suppress SLEEP bias and improve lifetime?

Architecture:
```
s_t (perception, 6+N dims)   → WorldEncoder  → z_world    [64]
h_t (live emotion dims, 4)   → InternalEncoder → z_internal [16]

concat(z_world, z_internal) + a_t → Predictor → z_next [64]
z_next + a_t                      → Critic    → emotion_pred [9]
```

where h_t = [hunger, sleep, pain, tedium] (live_emotion_dims [0,1,4,5]).

The IndividualAdapter stays on the WorldEncoder output only (same role as today).

L_pred target: `sg(WorldEncoder(s_{t+1}))` — prediction is still world-state only.
SIGReg loss: applied to z_world only.

Java inference: `WorldModelFilter.filter()` receives the creature's current live
emotion levels (already available in `FullAppraisal` from `EmotionalStimulus`);
passes them to `WorldModelEngine.predictEmotionalCost()`, which runs InternalEncoder
and concatenates before Predictor.

Sleep consolidation: `Engram.emotionAtDecision` is already captured — used as h_t
in `MemoryConsolidator.trainBatch()`.

Validation: same harness as Experiment A.

---

## Implementation phases

### Phase 1 — Training infrastructure

1. `simulations/train_p7_retrain.conf` — full world, no WORLD_MODEL filter
2. `docker/docker-compose-train-p7.yml` — 2 holders

### Phase 2 — Update Python data pipeline

`ml/scripts/prepare_dataset.py`:
- Add ROTTEN_APPLE to OBJECT_TYPES (currently missing)
- Add CACTUS, ALOE type bits to perception features
- Add `h_t` columns: join `initial_*` emotion values for live dims from
  `trajectory_emotions.csv` as the internal-state input for Experiment B
- Write two parquet variants: `train.parquet` (perception only) and
  `train_dual.parquet` (perception + internal state)
- Emit `internal_state_feature_order` and `internal_state_dim` in stats.json

### Phase 3 — Dual-encoder Python model

`ml/jepa/model.py`:
- Add `InternalEncoder(internal_state_dim, internal_latent_dim)` — small MLP,
  no LayerNorm needed given 4-dim input
- Add `DualSpeciesModel` that composes WorldEncoder + InternalEncoder + Predictor
  (input: `latent_dim + internal_latent_dim + action_dim`) + Critic

`ml/jepa/train.py`:
- `train_one_epoch` / `evaluate` accept an optional `internal_state` tensor;
  if provided, runs dual-encoder path

`ml/jepa/export.py`:
- Export 5 .pt files for dual model: `species_encoder`, `species_internal_encoder`,
  `species_predictor`, `species_critic`, `species_adapter`
- Add `internal_state_dim`, `internal_latent_dim`, `has_internal_encoder` to
  `model_contract.json`

`ml/scripts/train_species.py`:
- Add `--dual` flag to select `DualSpeciesModel`

### Phase 4 — Train + validate Experiment A

No Java changes. Retrain single-encoder model on full-world data, export, rebuild
jar, run validation docker-compose.

### Phase 5 — Java dual-encoder wiring

`ModelContract.java`:
- Add `internalStateDim`, `internalLatentDim`, `hasDualEncoder` fields

`MLServiceExtension.java` (`LoadedModels`):
- Conditionally load `species_internal_encoder.pt` when `hasDualEncoder=true`

`WorldModelEngine.java`:
- Add `internalEncoderPredictor` (null when `hasDualEncoder=false`)
- Overload `predictEmotionalCost(float[] percFeatures, float[] internalState,
  ActionType actionType)` — concatenates z_world + z_internal before Predictor

`WorldModelFilter.java`:
- Store current live emotion levels (set via a new `updateInternalState(float[])`
  called each filter cycle)
- Pass them to engine when `hasDualEncoder=true`

`FullAppraisal.java`:
- Extract live emotion levels from `EmotionalStimulus` before calling
  `actionSelection.selectOne()`, pass to `WorldModelFilter`

`MemoryConsolidator.java`:
- Add `encodeInternalState(Engram e)` — extracts live dims from
  `e.emotionAtDecision()`
- Update `trainBatch()` to run InternalEncoder and concatenate before Predictor
  when `hasDualEncoder=true`

### Phase 6 — Train + validate Experiment B

Retrain dual-encoder model on full-world data, export, rebuild jar, run validation.

### Phase 7 — Combined report

`docs/reports/EXP_48_SLEEP_BIAS_FIX.md`:
- Purpose / Assumptions / Hypothesis
- Experiment A results (lifetime vs EXP-43, SLEEP selection rate)
- Experiment B results (lifetime vs A, SLEEP selection rate)
- Conclusion: which fix is recommended and why

---

## File changes summary

| File | Change |
|------|--------|
| `simulations/train_p7_retrain.conf` | New — training sim, full world, no Mode-2 |
| `docker/docker-compose-train-p7.yml` | New — 2-holder training compose |
| `ml/scripts/prepare_dataset.py` | Full world types; h_t columns; dual parquet output |
| `ml/jepa/model.py` | Add InternalEncoder, DualSpeciesModel |
| `ml/jepa/train.py` | Dual-encoder training path |
| `ml/jepa/export.py` | 5-file export; extended model_contract |
| `ml/scripts/train_species.py` | `--dual` flag |
| `src/main/resources/models/species_*.pt` | New model artifacts (both A and B) |
| `src/main/resources/models/model_contract.json` | Extended for dual encoder |
| `ModelContract.java` | `internalStateDim`, `internalLatentDim`, `hasDualEncoder` |
| `MLServiceExtension.java` | Conditional InternalEncoder load |
| `WorldModelEngine.java` | Dual-encoder inference path |
| `WorldModelFilter.java` | Pass internal state to engine |
| `FullAppraisal.java` | Extract + forward live emotion levels |
| `MemoryConsolidator.java` | `encodeInternalState`; dual path in `trainBatch` |
| `docs/reports/EXP_48_SLEEP_BIAS_FIX.md` | Combined report |

---

## Acceptance criteria

- `mvn test` green (82 tests) after all Java changes
- Experiment A: median lifetime > EXP-43 (348 s), SLEEP rate documented
- Experiment B: median lifetime ≥ 4 000 s (P5-1 baseline ± 10 %), SLEEP rate < 20 %
