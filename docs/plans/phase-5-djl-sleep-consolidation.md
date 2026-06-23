# Phase 5 Implementation Plan — DJL Integration & Sleep-Gated Consolidation

**Epic:** #7  
**Issues:** #25 (MLServiceProvider), #26 (MemoryConsolidator), #27 (Circadian + hysteresis)  
**Depends on:** Phase 4 (eligibility traces / engrams — ✓ merged on main)

---

## 1. Context Summary

The creature already has:
- `Engram` records stored in `MemorySystemActor.engrams` (Phase 4)
- `CholinergicStimulus` → `HomeostaticRegulation` → sleep drive decrease (existing)
- `ActionType.SLEEP` produced by `FullAppraisal` when sleep drive is high (existing)
- TorchScript model files in `src/main/resources/models/` (`species_encoder.pt`, `species_predictor.pt`, `species_critic.pt`, `species_adapter.pt`, `model_contract.json`)
- No DJL dependency yet

Phase 5 wires the ML model into the JVM and makes the creature train its adapter during sleep.

---

## 2. Tasks

### Task 5.1 — MLServiceProvider: one model load per node, thread-safe inference (Issue #25)

**Design: Akka Extension + per-worker Predictor pool**

Sharing a static singleton or a direct object reference between actors is an Akka anti-pattern (bypasses supervision, breaks lifecycle, hides thread-safety issues). The correct pattern is an **Akka Extension** that loads the model once per `ActorSystem` (= once per JVM node) and exposes an `ActorRef` to a pool router of worker actors. All creature actors talk to the ML service via message passing only.

**New classes:**

- `br.cefetmg.lsi.l2l.creature.ml.ModelContract` — deserialises `model_contract.json`; validates `schema_version == 1` and model hash; throws `IllegalStateException` on mismatch (called during extension init, so it aborts at boot)

- `br.cefetmg.lsi.l2l.creature.ml.MLServiceExtension` — Akka Extension (`AbstractExtensionId<MLServiceExtension>`):
  - `createExtension(ActorSystem system)` loads the four TorchScript `Model` objects via DJL and validates `ModelContract`
  - Creates a round-robin pool router of `N` `MLWorkerActor`s (N = `Runtime.getRuntime().availableProcessors()`)
  - Exposes `ActorRef inferenceRouter()` — the only handle creatures need
  - Exposes `Model[] models()` — read-only, used by per-creature `MemoryConsolidator` to create its own `Trainer` during sleep training

- `br.cefetmg.lsi.l2l.creature.ml.MLWorkerActor` — pool member actor:
  - Creates its own `Predictor` instances in `preStart()` (one per loaded model)
  - Handles `InferenceRequest(float[] perceptionFeatures, float[] actionOneHot, ActorRef replyTo)` → runs inference → sends `InferenceResponse(float[] emotionDeltaPrediction)` back to `replyTo`
  - Single-threaded by actor guarantee; no shared `Predictor` across actors

- **Messages:** `InferenceRequest` and `InferenceResponse` (package-private records in `creature.ml`)

**DJL dependency (add to pom.xml):**
```xml
<dependency>
  <groupId>ai.djl</groupId>
  <artifactId>api</artifactId>
  <version>0.26.0</version>
</dependency>
<dependency>
  <groupId>ai.djl.pytorch</groupId>
  <artifactId>pytorch-engine</artifactId>
  <version>0.26.0</version>
</dependency>
<dependency>
  <groupId>ai.djl.pytorch</groupId>
  <artifactId>pytorch-native-auto</artifactId>
  <version>2.1.1</version>
  <scope>runtime</scope>
</dependency>
```

**Extension registration:** `MLServiceExtension.of(system)` initialises lazily on first call (`of` avoids the naming conflict with Akka's inherited instance method `get`). Called eagerly from `Holder.preStart()` so the model loads before any creature spawns.

**Acceptance (Issue #25):**
- Contract mismatch (wrong hash or `schema_version ≠ 1`) throws at `Holder.preStart()` with a clear message, aborting the node before any creature spawns
- 10 creature actors sending concurrent `InferenceRequest` messages produce correct, non-corrupted `InferenceResponse` values (verified by unit test with `TestKit`)

---

### Task 5.2 — MemoryConsolidator: sleep-gated, cancellable consolidation (Issue #26)

**New class:** `br.cefetmg.lsi.l2l.creature.ml.MemoryConsolidator` — Akka `UntypedActor` running on a new `wm-dispatcher` (pinned thread-pool, mirroring `bd-dispatcher`)

**Messages handled:**
- `SleepStarted` (new, package-private record in `creature.ml`) — triggers consolidation
- `WakeUp` (new) — sets `abortFlag`; if consolidation `CompletableFuture` is non-null, it was cancelled cooperatively (abort flag checked between batches inside `trainAdapter`)

**Consolidation protocol:**
1. On `SleepStarted`: clear `abortFlag`, pull `memory.getRecentEngrams(CONSOLIDATION_WINDOW)` (constant = 128), submit `CompletableFuture.runAsync(() -> engine.trainAdapter(engrams, abortFlag), wmExecutor)`
2. Batch loop inside `MemoryConsolidator.trainAdapter`: iterate engrams in mini-batches of 16; after each batch, check `abortFlag.get()` → break; all `NDArray` allocations inside `try (NDManager mgr = NDManager.newBaseManager())` block
3. On `WakeUp`: `abortFlag.set(true)`; future is not cancelled via `future.cancel()` (it wouldn't interrupt — see HLD §6 risk #5)

**Wiring in `HomeostaticRegulation`:** when a `CholinergicStimulus` reduces sleep drive to ≤ 0 (creature fully rested), send `WakeUp` to `MemoryConsolidator`. When `FullAppraisal` selects `ActionType.SLEEP` and emits a `CorticalStimulus` with `speed==0`, `Body` already emits `CholinergicStimulus`; the sleep *onset* signal is captured differently — see Task 5.3 below.

**Wiring in `CreatureActor`:**
- Instantiate `MemoryConsolidator` on `wm-dispatcher` alongside the other components; pass it `MLServiceExtension.get(context().system()).models()` at construction time
- Expose `worldModelActor()` accessor on `Creature` interface
- `FullAppraisal` sends `SleepStarted` to `MemoryConsolidator` the first cycle it selects SLEEP (stateful: track `lastActionWasSleep` flag)

**`application.conf` addition:**
```hocon
wm-dispatcher {
  type = PinnedDispatcher
  executor = "thread-pool-executor"
  thread-pool-executor { fixed-pool-size = 1 }
}
```

**Acceptance (Issue #26):**
- A wake-up mid-training (simulated by sending `WakeUp` after 1 ms) exits within one batch boundary (≤ 16 engrams processed after abort)
- After consolidation + wake, heap and native memory return to baseline (no `NDArray` leak — verified with `NDManager.debugDump()`)

---

### Task 5.3 — Circadian drive + anti-micro-nap hysteresis (Issue #27)

**Where the drive accumulates:** `EmotionalSystemActor` already tracks `SLEEP` emotion level via a set of `Emotion` objects. Currently sleep drive presumably accumulates uniformly (via `AdrenergicStimulus` or constant drift). This task adds a **circadian modulation** to the accumulation rate.

**Design:**
- Add `CircadianClock` plain-Java class in `creature.components`: maintains `phase` (0…2π, advances by `2π / CIRCADIAN_PERIOD_TICKS` per decision cycle); exposes `double driveRate()` = `BASE_SLEEP_DRIVE + AMPLITUDE * sin(phase)` — no global state, no wall-clock
- `CIRCADIAN_PERIOD_TICKS = 200` (decision cycles), `BASE_SLEEP_DRIVE = 0.02`, `AMPLITUDE = 0.01` — add all to `Constants`
- `PartialAppraisal` (or `EmotionalSystemActor`) calls `circadian.driveRate()` each tick and adds that as a `AdenosinergicStimulus` (new minimal stimulus, or reuse `AdrenergicStimulus` with a tagged delta only on the SLEEP dimension — design choice: reuse is simpler)

**Anti-micro-nap hysteresis in `FullAppraisal`:**
- Add `int sleepDwellTicks` counter and `boolean inSleep` flag (both `private`, not shared state)
- On selecting SLEEP: if `!inSleep` → set `inSleep = true`, `sleepDwellTicks = 0`; if `inSleep` → `sleepDwellTicks++`
- On selecting any non-SLEEP action: only allow transition to non-SLEEP if `sleepDwellTicks >= MIN_SLEEP_TICKS` (constant = 10 cognitive cycles, in `Constants`); otherwise override action selection back to SLEEP
- Add `MIN_SLEEP_TICKS` to `Constants`

**Acceptance (Issue #27):**
- Logged sleep episodes (`SleepStarted` / `WakeUp` pairs) are always ≥ `MIN_SLEEP_TICKS` long
- No micro-naps (isolated single-cycle SLEEP choices) in a 5000-cycle baseline run

---

## 3. Implementation Order

```
5.1 (MLServiceExtension + MLWorkerActor pool + DJL wiring)
  → 5.2 (MemoryConsolidator, wm-dispatcher, SleepStarted/WakeUp messages)
    → 5.3 (CircadianClock, hysteresis in FullAppraisal)
```

5.1 is a prerequisite for 5.2 (engine must exist before actor uses it). 5.3 is independent of 5.1/5.2 but should be implemented last so integration tests can use contiguous sleep episodes.

---

## 4. Files to Create

| File | Purpose |
|------|---------|
| `creature/ml/ModelContract.java` | JSON deserialization + validation of `model_contract.json` |
| `creature/ml/MLServiceExtension.java` | Akka Extension: loads Model[] once per JVM, owns inference router |
| `creature/ml/MLWorkerActor.java` | Pool member: owns its own Predictor, handles InferenceRequest |
| `creature/ml/InferenceRequest.java` | Message: perception features + action one-hot + replyTo |
| `creature/ml/InferenceResponse.java` | Message: predicted emotion delta array |
| `creature/ml/MemoryConsolidator.java` | Per-creature actor: SleepStarted→trainAdapter, WakeUp→abort |
| `creature/ml/SleepStarted.java` | Message record |
| `creature/ml/WakeUp.java` | Message record |
| `creature/components/CircadianClock.java` | Phase-based drive rate modulator |

## 5. Files to Modify

| File | Change |
|------|--------|
| `pom.xml` | Add DJL api + pytorch-engine + pytorch-native-auto dependencies |
| `application.conf` | Add `wm-dispatcher` |
| `Constants.java` | Add `CIRCADIAN_PERIOD_TICKS`, `BASE_SLEEP_DRIVE`, `AMPLITUDE`, `MIN_SLEEP_TICKS`, `CONSOLIDATION_WINDOW` |
| `Holder.java` | Call `MLServiceExtension.get(system)` in `preStart()` to eagerly load model at node startup |
| `CreatureActor.java` | Instantiate `MemoryConsolidator` (passing `MLServiceExtension.get(system).models()`); expose `memoryConsolidator()` on `Creature` interface |
| `Creature.java` | Add `memoryConsolidator()` accessor |
| `FullAppraisal.java` | Send `SleepStarted` on sleep onset; enforce `MIN_SLEEP_TICKS` hysteresis |
| `EmotionalSystemActor.java` | Wire `CircadianClock`, apply drive rate each tick |

---

## 6. Mini-Experiment Plan (EXP-P5-1)

**Hypothesis:** With sleep-gated adapter consolidation and anti-micro-nap hysteresis, sleep episodes are contiguous (≥ `MIN_SLEEP_TICKS`) and native memory returns to baseline after each episode.

**Metrics to collect:** per-creature log of `(creatureId, sleepStartCycle, wakeUpCycle, episodeLength, heapMBAfterWake)` via extractor.

**Sample size:** 10 creatures × one simulation run of 10 000 cycles.

**Success criteria:** zero micro-naps, zero native memory leaks (delta ≤ 5 MB after wake vs. before sleep).

---

## 7. Corrections (post-implementation review)

Several architectural decisions were revised after the initial implementation exposed bugs. This section documents the authoritative design rationale.

### 7.1 Per-creature adapter (not shared)

The original plan passed `MLServiceExtension.LoadedModels` (including a single shared adapter `ZooModel`) to every `MemoryConsolidator`. This caused two correctness bugs:

1. **Inplace-operation version mismatch**: Adam's `step()` modifies parameters in-place. If creature A's `step()` fires while creature B's active `backward()` still holds references into the same native tensors, PyTorch raises `RuntimeError: one of the variables needed for gradient computation has been modified by an inplace operation`.
2. **Cross-creature contamination**: creature 180 was being trained on creature 182's memory replay.

**Fix**: each `MemoryConsolidator.preStart()` loads its own `ZooModel` copies for all four models with `trainParam=true`. The shared `LoadedModels.adapter` field was removed; only `encoder`, `predictor`, and `critic` remain shared for inference.

### 7.2 Single-threaded training executor (not GRAD_LOCK)

An initial workaround used a `static final Object GRAD_LOCK` shared by all `MemoryConsolidator` instances. This is an Akka anti-pattern: a static shared object bypasses the supervision hierarchy and breaks actor isolation.

**Fix**: `MLServiceExtension.Impl` exposes a `trainingExecutor()` — a `newSingleThreadExecutor` whose daemon thread is named `djl-training`. All creatures' `CompletableFuture.supplyAsync()` calls target this executor, serialising backward passes at the JVM level without any shared mutable state in the actor classes.

### 7.3 Full prediction-error chain (not reconstruction loss)

The original `trainBatch` computed `MSE(adapter(encoder(perc)), encoder(perc))` — a reconstruction loss that trains the adapter to be an identity transform, which is biologically meaningless and does not optimize for prediction accuracy.

**Fix**: the loss is now `MSE(critic(predictor(adapter(encoder(perc)), action)), actual_emotionDelta) × eligibility`. This is the full JEPA prediction-error chain. Gradient flows through all four models (all loaded with `trainParam=true`); only the adapter's optimizer calls `step()` — the encoder, predictor, and critic are frozen (their gradients accumulate but are never applied).

### 7.4 cognitiveCycle batching bug

`cognitiveCycle++` and `memorySystem.tickDecisionCycle()` were positioned before the `for` loop over stimuli in `FullAppraisal.onReceive()`. When multiple stimuli arrived in one `onReceive` call, they all shared the same cycle counter, causing WAKE and SLEEP events to be logged at the same cycle number.

**Fix**: both increments were moved inside `if (stimulus instanceof EmotionalStimulus)` so each decision cycle is counted exactly once per processed emotional stimulus.

### 7.5 adapter forward arity (1 arg, not 2)

The original `trainBatch` called `trainer.forward(new NDList(z, z))` with two arguments. The TorchScript adapter's Python signature is `forward(self, z: Tensor) -> Tensor` — one non-self argument. Passing two raised `Expected at most 2 argument(s) for operator 'forward', but received 3`.

**Fix**: the adapter Trainer is called as `adapterTrainer.forward(new NDList(z))`.

### 7.6 Epic 6 inference hook

`InferenceRequest` was extended with an `encodedLatent` field (null in Phase 5). In Epic 6, `WorldModelFilter` will apply the per-creature adapter and pass the pre-adapted latent directly, allowing `MLWorkerActor` to skip the encoder for creatures that have been personalised. `MLWorkerActor.runInference()` branches on `req.encodedLatent() != null`.
