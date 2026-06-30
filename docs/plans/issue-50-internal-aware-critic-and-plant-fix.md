# Issue #50 — Internal-Aware Critic + Plant Collision Fix

## Problem statement

Two root bugs remain after EXP-48:

1. **Plants have null collision shapes** (`ObjectGeometry.java` only branches on `FruitType`; `PlantType` falls into `else { shape = null; }`). Creatures never perceive CACTUS or ALOE because they are never inserted into the QuadTree with a valid shape. The full interaction pipeline (passive pain, healing, eat-active-pain) is already implemented in `Mouth` and `Plant`; only the geometry registration is missing.

2. **Critic is blind to internal state** (`DualSpeciesModel` feeds `z_internal` to the Predictor but the Critic still takes only `(z_next, action)`). The Critic cannot distinguish "SLEEP when hunger=0.8" from "SLEEP when hunger=0.1", so it always selects SLEEP when arousal > 4.5 (Mode-2 threshold). EXP-48B confirmed this: SLEEP rate unchanged at 94.4%.

## Fix 1 — ObjectGeometry: PlantType collision shape

**File:** `src/main/java/br/cefetmg/lsi/l2l/physics/ObjectGeometry.java`

In the constructor, add a `PlantType` branch mirroring the `FruitType` one:

```java
if (type instanceof FruitType fruitType) {
    this.shape = new Circle((float) point.x, (float) point.y, (float) fruitType.radius);
} else if (type instanceof PlantType plantType) {
    this.shape = new Circle((float) point.x, (float) point.y, (float) plantType.radius);
} else {
    shape = null;
}
```

In `getBoundingBox()`, add the same:
```java
if (type instanceof FruitType fruitType)
    return new Rectangle(...fruitType.radius...);
else if (type instanceof PlantType plantType)
    return new Rectangle(...plantType.radius...);
else return null;
```

No other changes needed: `SensoryCortex`, `Mouth`, `Plant`, `FullAppraisal`, and `WorldModelFilter` already handle `PlantType` correctly.

## Fix 2 — DualSpeciesModel: internal-aware Critic

**File:** `ml/jepa/model.py`

Update `DualSpeciesModel`:
- Critic input becomes `latent_dim + internal_latent_dim = 80` instead of `latent_dim = 64`
- Pass `concat(z_next, z_internal)` to Critic instead of just `z_next`

```python
# in DualSpeciesModel.__init__:
self.critic = Critic(combined_latent_dim, action_dim, emotion_dim,
                     min_arousal, max_arousal, hidden)

# in DualSpeciesModel.forward:
emotion = self.critic(torch.cat([z_next, z_internal], dim=-1), a_t)
```

**File:** `ml/jepa/train.py`

No change needed — the training loop calls `model(s_t, h_t, a_t)` and unpacks `(z_world, z_next, emotion_pred)`.

**File:** `ml/jepa/export.py`

The Critic export is a single `torch.jit.trace` call on `model.critic`. The traced input must now be `(z_combined[B, 80], action[B, 9])` instead of `(z_next[B, 64], action[B, 9])`. Update the example inputs accordingly.

**File:** `src/main/java/br/cefetmg/lsi/l2l/creature/ml/WorldModelEngine.java`

In the dual inference path, the Critic call must now pass `concat(z_next, z_internal)`:

```java
// critic takes [z_next | z_internal] concat → emotion_pred
NDArray criticInput = NDArrays.concat(new NDList(zNext, zInternal), 1);
NDArray emotionPred = criticPredictor.predict(new NDList(criticInput, actionVec)).singletonOrThrow();
```

Update `model_contract.json` to add `"critic_input_dim": 80` (or derive it as `latent_dim + internal_latent_dim`). Alternatively the Java code can compute it from existing fields.

## Training plan

1. **Collect new training data** — run `docker-compose-train-p7.yml` (already fixed to combined JVM and 6-object world). This time CACTUS/ALOE will appear. Extract to `data/train_p8/`.

2. **Prepare dataset** — `ml/scripts/prepare_dataset.py --data data/train_p8 --dual` (no changes needed; h_t derivation already correct).

3. **Train** — `ml/scripts/train_species.py --dual --ckpt checkpoints/exp_b2` (dual-encoder with internal-aware Critic).

4. **Collapse check** — `ml/scripts/check_collapse.py --dual --ckpt checkpoints/exp_b2`.

5. **Export** — `ml/scripts/export_model.py --dual --ckpt checkpoints/exp_b2` → updates `src/main/resources/models/`.

6. **Build** — `mvn package` (must pass 90 tests).

7. **Validate** — run `docker-compose-exp-48-val.yml`, extract, run `analysis/exp_48_sleep_bias_fix.py`. Target: SLEEP rate in Mode-2 < 20%.

## Test plan

- `mvn test` — 90/90 pass after ObjectGeometry fix (no model change yet)
- `ConsolidationPipelineTest` — already handles dual-encoder; will exercise the new Critic shape automatically once model is updated
- Mini-experiment validation run with plants in the world

## Expected outcome

- Plants (CACTUS/ALOE) become visible and collidable; creatures learn avoidance via operant conditioning
- Critic conditioned on internal state: when hunger is high, SLEEP cost increases relative to EAT/APPROACH, reducing SLEEP selection rate in Mode-2
- SLEEP rate target: < 20% (from 94.4% baseline)
