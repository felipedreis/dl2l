# Phase 6 — Mode-2 Deliberative Action Selection

**Epic:** #8  
**Issues:** #37 (6.0), #28 (6.1), #29 (6.2), #30 (6.3)  
**Depends on:** Phase 5 (DJL sleep-gated adapter consolidation)

---

## 1. Goal

Add a `WorldModelFilter` to the existing action-selection chain that uses the trained JEPA world model — **including the per-creature trained adapter** — to score candidate actions by their predicted emotional cost. The filter is optional and non-blocking: every gate failure returns the input list unchanged, preserving exact Mode-1 behaviour.

Sequencing: **6.0 → 6.1 → 6.2 → 6.3**, strictly in order.

---

## 2. Current State

| Item | State |
|------|-------|
| `EmotionalSystemActor` | 2 live dims: HUNGER, SLEEP. 7 dims missing. `getMaxComplexArousal()` throws. |
| `model_contract.json` | `emotion_index_order` (9 entries) and `live_emotion_dims: [0,1]` already present. `baseline_pred_error`/`ood_threshold_multiplier` absent. |
| `ModelContract.java` | Deserialises `schema_version`, `input_dim`, `latent_dim`, `action_dim`, `emotion_dim`, `model_hash` only. |
| `MLServiceExtension` | Loads shared species encoder, predictor, critic. No per-creature adapter registry. |
| `MemoryConsolidator` | Loads its own adapter ZooModel in `preStart()` — trained weights are invisible at inference time (the "learn to live" loop is open). |
| `ActionSelection` chain | `TargetDistanceFilter → ActionProbabilityFilter → RandomFilter` |
| `ActionSelectionType` | `TARGET_DISTANCE, AFFORDANCE, MEMORY, RANDOM, SHORT_TERM_MEMORY` |

---

## 3. Task 6.0 — Per-creature adapter sharing (#37)

### Problem

`MemoryConsolidator` trains the per-creature adapter during sleep; `WorldModelEngine` must use those trained weights at inference during waking. Currently `MemoryConsolidator` loads its own ZooModel copy — training updates never reach inference. The "learn to live" loop cannot close without this.

### Solution

Add a per-creature adapter registry to `MLServiceExtension.Impl`:

```java
private final ConcurrentHashMap<Long, ZooModel<NDList, NDList>> perCreatureAdapters
        = new ConcurrentHashMap<>();

public ZooModel<NDList, NDList> getOrCreateAdapter(long creatureKey) {
    return perCreatureAdapters.computeIfAbsent(creatureKey, k -> {
        try {
            return loadTrainable(modelDir, "species_adapter");
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load adapter for creature " + k, e);
        }
    });
}

public void releaseAdapter(long creatureKey) {
    ZooModel<NDList, NDList> model = perCreatureAdapters.remove(creatureKey);
    if (model != null) model.close();
}
```

The `loadTrainable` helper already exists in `MemoryConsolidator`; extract it to `MLServiceExtension`.

### Thread-safety invariant

- **Writes** (parameter updates via `adapterTrainer.step()`) run only on `mlExt.trainingExecutor()` — a single-threaded executor — during sleep.
- **Reads** (forward pass via `adapterPredictor.predict()`) run only on the creature actor's component-dispatcher thread during waking.
- Temporal separation is enforced by the sleep/wake state machine in `FullAppraisal` + the `abortFlag` cooperative-abort in `MemoryConsolidator`.

The transition window (WakeUp received, training possibly completing its last batch) is bounded and safe: the worst case is that `WorldModelEngine` sees the weights from `N+1` optimizer steps instead of `N` — a small, continuous update that does not cause incorrect predictions, only slightly premature ones.

### File changes

| File | Change |
|------|--------|
| `MLServiceExtension.java` | Add `getOrCreateAdapter()`, `releaseAdapter()`, extract `loadTrainable()` as a private static helper |
| `MemoryConsolidator.java` | In `preStart()`, replace `loadTrainable(modelDir, "species_adapter")` with `mlExt.getOrCreateAdapter(creatureKey)`. Remove per-creature adapter loading; open a `Trainer` from the shared ZooModel. |
| `CreatureActor.java` | In `kill()`, add `MLServiceExtension.of(TypedActor.context().system()).releaseAdapter(id.key)` |

### Acceptance

- A creature that completes one sleep episode uses trained adapter weights in subsequent inference.
- Adapter of creature A is never returned for creature B.
- `releaseAdapter()` closes the ZooModel; no native-memory leak on creature death.

---

## 4. Task 6.1 — Populate the emotion vector (#28)

### 4.1 Constants

Add 7 string constants to `Constants.java`:

```java
String PAIN      = "pain";
String FEAR      = "fear";
String STRESS    = "stress";
String APATHY    = "apathy";
String TEDIUM    = "tedium";
String CURIOSITY = "curiosity";
String FERTILITY = "fertility";
```

### 4.2 EmotionalSystemActor

Add the 7 remaining emotions to the constructor as constant-zero placeholders. Order must match `model_contract.json → emotion_index_order`:

```
hunger(0), sleep(1), apathy(2), stress(3), pain(4), tedium(5), fear(6), curiosity(7), fertility(8)
```

Implement `getMaxComplexArousal()` — return the `Emotion` with the highest level among all emotions whose name is NOT `HUNGER` and NOT `SLEEP`. If all complex dims are at `MIN_AROUSAL_LEVEL`, return the globally highest-level emotion as a fallback (never throws).

### 4.3 ModelContract

Extend `ModelContract` with new deserialisable fields:

```java
@JsonProperty("emotion_index_order")      public List<String> emotionIndexOrder;
@JsonProperty("live_emotion_dims")        public List<Integer> liveEmotionDims;
@JsonProperty("action_index_order")       public List<String> actionIndexOrder;
@JsonProperty("min_arousal")              public double minArousal;
@JsonProperty("max_arousal")              public double maxArousal;
@JsonProperty("baseline_pred_error")      public double baselinePredError      = 1.0;
@JsonProperty("ood_threshold_multiplier") public double oodThresholdMultiplier = 2.0;
```

Add helpers:
```java
public int emotionIndexOf(String name) {
    int idx = emotionIndexOrder.indexOf(name);
    if (idx < 0) throw new IllegalStateException("Emotion '" + name + "' not in contract");
    return idx;
}
```

### 4.4 model_contract.json

Add two new keys (existing keys are already correct):
```json
"baseline_pred_error": 1.0,
"ood_threshold_multiplier": 2.0
```

(`baseline_pred_error = 1.0` with `oodThresholdMultiplier = 2.0` means OOD self-disable triggers at EMA > 2.0 — effectively off until we measure real baseline error after training runs.)

### 4.5 Acceptance

- `EmotionalSystemActor` constructor registers exactly 9 emotions.
- `getMaxComplexArousal()` never throws.
- `ModelContract.emotionIndexOf("pain")` returns `4`.
- `ModelContract` deserialises `emotion_index_order`, `action_index_order`, `min_arousal`, `max_arousal` correctly.

---

## 5. Task 6.2 — WorldModelEngine.predictEmotionalCost (#29)

### 5.1 Design

`WorldModelEngine` is a **per-creature, non-actor class** owned by `FullAppraisal`. It holds synchronous Predictor objects for all four models:

- **encoder**: from shared species `MLServiceExtension.models().encoder()` (same ZooModel for all creatures; each opens its own Predictor — safe because Predictor is not thread-shared)
- **adapter**: from per-creature `mlExt.getOrCreateAdapter(creatureKey)` — this is the ZooModel trained by `MemoryConsolidator` during sleep; inference reads the latest trained weights
- **predictor**: from shared species `MLServiceExtension.models().predictor()`
- **critic**: from shared species `MLServiceExtension.models().critic()`

This matches the `MLWorkerActor` Epic-6 design: `WorldModelEngine` applies encoder → adapter, then passes the adapted latent to the next model stage.

### 5.2 WorldModelEngine class

**Package:** `br.cefetmg.lsi.l2l.creature.ml`

```java
public class WorldModelEngine implements AutoCloseable {

    // Must match model_contract.json action_index_order (alphabetical)
    private static final ActionType[] ACTION_ORDER = {
        APPROACH, AVOID, EAT, ESCAPE, PLAY, SLEEP, TOUCH, TURN, WANDER
    };

    // Aversive dims per model_contract.json emotion_index_order
    private static final Set<String> AVERSIVE_DIMS = Set.of("pain", "fear");

    private final Predictor<NDList, NDList> encoderPredictor;
    private final Predictor<NDList, NDList> adapterPredictor;
    private final Predictor<NDList, NDList> predictorPredictor;
    private final Predictor<NDList, NDList> criticPredictor;
    private final ModelContract contract;

    // OOD gating — rolling EMA per prediction_error_monitor.md
    private double latentPredErrorEma;   // initialised to baselinePredError
    private final double emaAlpha;       // 2 / (N+1), N = 100

    public WorldModelEngine(MLServiceExtension.Impl mlExt, long creatureKey) {
        this.contract = mlExt.models().contract();
        // Validate action order at construction (fail fast, not silently wrong)
        for (int i = 0; i < ACTION_ORDER.length; i++) {
            String expected = contract.actionIndexOrder.get(i);
            if (!ACTION_ORDER[i].name().equals(expected))
                throw new IllegalStateException(
                        "Action index mismatch at " + i + ": expected " + expected);
        }
        MLServiceExtension.LoadedModels m = mlExt.models();
        this.encoderPredictor   = m.encoder().newPredictor();
        this.adapterPredictor   = mlExt.getOrCreateAdapter(creatureKey).newPredictor();
        this.predictorPredictor = m.predictor().newPredictor();
        this.criticPredictor    = m.critic().newPredictor();
        this.latentPredErrorEma = contract.baselinePredError;
        this.emaAlpha           = 2.0 / (100 + 1);
    }

    /**
     * Synchronous inference: perception → encoder → adapter → predictor(action) → critic.
     * Returns null on TranslateException (callers treat null as Mode-1 fallback).
     */
    public PredictedEmotionalState predictEmotionalCost(float[] perceptionFeatures,
                                                         ActionType actionType) {
        try (NDManager mgr = NDManager.newBaseManager()) {
            NDArray perc       = mgr.create(perceptionFeatures);
            NDArray latent     = encoderPredictor.predict(new NDList(perc)).singletonOrThrow();
            NDArray adapted    = adapterPredictor.predict(new NDList(latent)).singletonOrThrow();
            NDArray actionHot  = mgr.create(encodeAction(actionType));
            NDArray nextLatent = predictorPredictor.predict(
                                     new NDList(adapted, actionHot)).singletonOrThrow();
            float[] deltas     = criticPredictor.predict(
                                     new NDList(nextLatent, actionHot))
                                     .singletonOrThrow().toFloatArray();
            return buildState(deltas);
        } catch (TranslateException e) {
            return null;
        }
    }

    /** Cost = sum of predicted levels for AVERSIVE_DIMS (pain + fear). */
    public double aversiveCost(PredictedEmotionalState predicted) {
        double cost = 0;
        for (String dim : AVERSIVE_DIMS)
            cost += predicted.level(contract.emotionIndexOf(dim));
        return cost;
    }

    public boolean isOodSelfDisabled() {
        return latentPredErrorEma > contract.oodThresholdMultiplier * contract.baselinePredError;
    }

    @Override
    public void close() {
        encoderPredictor.close();
        adapterPredictor.close();
        predictorPredictor.close();
        criticPredictor.close();
    }

    // -----------------------------------------------------------------------

    private float[] encodeAction(ActionType action) {
        float[] hot = new float[contract.actionDim];
        for (int i = 0; i < ACTION_ORDER.length; i++) {
            if (ACTION_ORDER[i] == action) { hot[i] = 1f; break; }
        }
        return hot;
    }

    private PredictedEmotionalState buildState(float[] deltas) {
        double[] levels = new double[contract.emotionDim];
        for (int i = 0; i < contract.emotionDim && i < deltas.length; i++) {
            // tanh output in [-1,1]; map linearly to [minArousal, maxArousal]
            double normalised = (deltas[i] + 1.0) / 2.0;
            levels[i] = contract.minArousal
                        + normalised * (contract.maxArousal - contract.minArousal);
            levels[i] = Math.max(contract.minArousal,
                                 Math.min(contract.maxArousal, levels[i]));
        }
        return new PredictedEmotionalState(levels);
    }
}
```

### 5.3 PredictedEmotionalState record

**Package:** `br.cefetmg.lsi.l2l.creature.ml`

```java
public record PredictedEmotionalState(double[] levels) {
    public double level(int index) { return levels[index]; }
}
```

Lightweight value holder — no JPA annotations, no overhead.

### 5.4 Acceptance

- `WorldModelEngine.predictEmotionalCost(features, EAT)` returns a non-null `PredictedEmotionalState` with all dims in `[minArousal, maxArousal]`.
- Action index order validated against contract at construction — throws `IllegalStateException` on mismatch.
- `close()` releases all four Predictors.
- Inference failure returns `null`; callers degrade to Mode-1.

---

## 6. Task 6.3 — WorldModelFilter (#30)

### 6.1 ActionSelectionType

Add `WORLD_MODEL` to the enum.

### 6.2 HIGH_AROUSAL_THRESHOLD

From trial_5 arousal data (n=106,405 regulation events, 16 creatures):

| Threshold | Fraction of cycles triggering Mode-2 |
|-----------|---------------------------------------|
| 4.0       | 32.7% |
| 4.5       | 26.2% |
| 5.0       | 20.6% |

**Chosen: `4.5`** (p75 = 4.60) — triggers on the top ~26% of cycles, corresponding to clearly elevated need. Expose as a named constant so it can be adjusted without recompile.

### 6.3 WorldModelFilter class

**Package:** `br.cefetmg.lsi.l2l.creature.actionSelector`

```java
public class WorldModelFilter implements ActionFilter {

    // Hard cap: no more than this many inference calls per filter invocation.
    static final int INFERENCE_BUDGET = 16;

    // Only deliberate when emotion exceeds this level (p75 of trial_5 data = 4.60).
    static final double HIGH_AROUSAL_THRESHOLD = 4.5;

    private final WorldModelEngine engine;
    private final ModelContract contract;

    public WorldModelFilter(WorldModelEngine engine, ModelContract contract) {
        this.engine   = engine;
        this.contract = contract;
    }

    @Override
    public List<Action> filter(List<Action> actions, Emotion toRegulate) {
        // Gate 1 — Mode-2 frequency gate
        if (actions.size() <= 1 || toRegulate.getLevel() < HIGH_AROUSAL_THRESHOLD)
            return actions;

        // Gate 2 — Inference budget
        if (actions.size() > INFERENCE_BUDGET)
            return actions;

        // Gate 3 — OOD confidence gate (prediction_error_monitor.md §Self-disable)
        if (engine.isOodSelfDisabled())
            return actions;

        // Score candidates
        List<ScoredAction> scored = new ArrayList<>(actions.size());
        for (Action action : actions) {
            float[] features = encodePerception(action.perception);
            PredictedEmotionalState prediction =
                    engine.predictEmotionalCost(features, action.type);
            if (prediction == null)
                return actions;                  // inference error → full Mode-1 fallback
            scored.add(new ScoredAction(action, engine.aversiveCost(prediction)));
        }

        // Sort ascending: lowest aversive cost = most preferred
        scored.sort(Comparator.comparingDouble(s -> s.cost));
        return scored.stream().map(s -> s.action).collect(Collectors.toList());
    }

    @Override
    public ActionSelectionType getFilterType() {
        return ActionSelectionType.WORLD_MODEL;
    }

    // Encode perception into model_contract.json → perception_feature_order
    private float[] encodePerception(Perception perception) {
        float[] f = new float[contract.inputDim];
        f[0] = (float) perception.distance;
        f[1] = (float) perception.angle;
        f[2] = (float) Math.sin(perception.angle);
        if (perception.objectType.isDefined()) {
            Object type = perception.objectType.get();
            if (type == FruitType.GRAY_APPLE)  f[3] = 1f;
            if (type == FruitType.GREEN_APPLE) f[4] = 1f;
            if (type == FruitType.RED_APPLE)   f[5] = 1f;
        }
        return f;
    }

    private record ScoredAction(Action action, double cost) {}
}
```

### 6.4 FullAppraisal changes

**New field:**
```java
private WorldModelEngine worldModelEngine;
```

**`preStart()` additions:**
```java
try {
    MLServiceExtension.Impl mlExt = MLServiceExtension.of(context().system());
    ModelContract contract = ModelContract.load(mlExt.modelDir());
    worldModelEngine = new WorldModelEngine(mlExt, id.key);

    actionSelection = new ActionSelection(
        new TargetDistanceFilter(),
        new ActionProbabilityFilter(creature.operantConditioning()),
        new WorldModelFilter(worldModelEngine, contract),   // Mode-2 filter
        new RandomFilter()
    );
} catch (Exception e) {
    // ML init failed — log and crash. Running without the configured model
    // would produce an inconsistent experiment (Mode-1 with ML config = data mismatch).
    logger.severe("FullAppraisal: ML init failed — crashing as configured: " + e.getMessage());
    throw new RuntimeException("ML service init failed", e);
}
```

If `MLServiceExtension` is NOT configured (no model files), the exception propagates and the creature actor crashes — this is intentional. An experiment configured to run with the world model must have the model files.

**`postStop()` addition:**
```java
if (worldModelEngine != null) worldModelEngine.close();
```

### 6.5 Graceful-degradation invariants (all three gates)

| Gate | Condition | Behaviour |
|------|-----------|-----------|
| Mode-2 frequency | `actions.size() <= 1` or `emotion.level < 4.5` | Skip CEM, return input |
| Inference budget | `actions.size() > 16` | Skip CEM, return input |
| OOD confidence | `EMA > 2 × baseline_pred_error` | Skip CEM for this cycle only |
| Inference failure | `predictEmotionalCost()` returns null | Return entire input unchanged |

### 6.6 Acceptance criteria

- Per-cycle latency within budget (≤ `INFERENCE_BUDGET` forward passes per cycle at most).
- Forcing `HIGH_AROUSAL_THRESHOLD = Double.MAX_VALUE` (or low confidence) reproduces exact Mode-1 output.
- No concurrency errors under multi-creature load: each creature has its own `WorldModelEngine` with its own four Predictors.

---

## 7. File change summary

| File | Task | Change |
|------|------|--------|
| `creature/ml/MLServiceExtension.java` | 6.0 | Add adapter registry (`getOrCreateAdapter`, `releaseAdapter`); extract `loadTrainable` |
| `creature/ml/MemoryConsolidator.java` | 6.0 | Use `mlExt.getOrCreateAdapter(creatureKey)` instead of loading own adapter |
| `creature/CreatureActor.java` | 6.0 | `kill()`: call `releaseAdapter(id.key)` |
| `common/Constants.java` | 6.1 | Add 7 emotion name constants |
| `creature/components/EmotionalSystemActor.java` | 6.1 | Add 7 placeholder emotions; implement `getMaxComplexArousal()` |
| `creature/ml/ModelContract.java` | 6.1 | Add `emotionIndexOrder`, `liveEmotionDims`, `actionIndexOrder`, `minArousal`, `maxArousal`, `baselinePredError`, `oodThresholdMultiplier`; add `emotionIndexOf()` |
| `src/main/resources/models/model_contract.json` | 6.1 | Add `baseline_pred_error: 1.0`, `ood_threshold_multiplier: 2.0` |
| `creature/ml/PredictedEmotionalState.java` | 6.2 | **New** — lightweight record `(double[] levels)` |
| `creature/ml/WorldModelEngine.java` | 6.2 | **New** — per-creature synchronous 4-model inference pipeline |
| `creature/actionSelector/WorldModelFilter.java` | 6.3 | **New** — budgeted, gated Mode-2 filter |
| `creature/bd/ActionSelectionType.java` | 6.3 | Add `WORLD_MODEL` |
| `creature/components/FullAppraisal.java` | 6.3 | Init `WorldModelEngine`; wire `WorldModelFilter`; close engine in `postStop()` |
