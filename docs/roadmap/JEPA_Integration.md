# JEPA / World-Model Integration Roadmap — v6 (consolidated)

**What changed from v5.** v6 folds in everything established in design discussion after v5
was written: the credit-assignment reframing, the eligibility-trace decision on the creature's
own cognitive clock, and the measurement-first instrumentation phase. It is renumbered into a
single linear phase sequence, and every task now carries **real signatures and file references
discovered from the actual `felipedreis/dl2l` source** so a coding agent has maximum context
without re-deriving the codebase.

**Read first:** the companion `HLD_JEPA_World_Model_Integration.md` for *why*. This document is
*what* and *how*. The cross-cutting invariants below are binding on every task.

---

## Cross-cutting invariants (binding)

1. **No global clock; no reliance on cross-actor message ordering.** The system is
   time-continuous with no tick. Akka guarantees order only per single sender→receiver pair,
   and the custom batching mailbox strips sender identity (recovered via a `SequentialId →
   ActorRef` table in the creature supervisor) and batches stimuli into one list per
   `onReceive` with no sub-order guarantee. Any "when" uses the creature's **own cognitive-cycle
   count** (one `onReceive` pass), never wall-clock, never a global counter.

2. **Messages are deeply immutable.** Anything sent between actors is an immutable value object
   or a deep copy. The project README already states this Akka requirement. `Collections.
   unmodifiableList` is a shallow wrapper and is not sufficient. Note `ShortTermMemory` is
   already `final`-fielded and `Serializable` — preserve that discipline for new message types.

3. **The world model is always optional.** `WorldModelFilter` is one `ActionFilter` in the
   existing chain (`TargetDistanceFilter` → `ActionProbabilityFilter` → `RandomFilter`).
   Uninitialised, mid-consolidation, or low-confidence → it returns possibilities unmodified and
   the creature runs pure Mode-1. It never throws and never blocks the cognitive cycle.

4. **A versioned model contract.** The Python exporter and the Java loader share one
   `model_contract.json` (input_dim, latent_dim, action_dim, emotion index order, value ranges,
   model hash), loaded and asserted at boot. Mismatch aborts startup loudly.

5. **A per-cycle inference budget.** Mode-2 planning has a hard cap on NN forward passes per
   creature per cognitive cycle. Over budget → Mode-1 fallback for that cycle. Protects the OL2A
   behavioural-congruence property.

---

## Phase 0 — De-risk before building

### Task 0.1 — Coverage probe
Run a baseline simulation; characterise the state distribution the reactive (Mode-1) policy
actually visits (per-dimension ranges, PCA/histogram of `Perception` vectors). LeWorldModel
assumes trajectories "sufficiently cover the dynamics"; a forager's may not. Decide whether
random-policy episodes must be mixed into training data (Task 2.1).
**Acceptance:** coverage report; recorded decision on adding exploratory episodes.

### Task 0.2 — Online prediction-error monitor (design only; wired in Task 6.3)
Define the metric: latent prediction error of the species model on *live* engrams as formed.
Becomes the runtime confidence signal (invariant #3) and the distribution-shift early warning.
**Acceptance:** written metric definition + self-disable threshold.

---

## Phase 1 — Instrumentation: settle reinforcement granularity *(was the Phase 0.5 addendum)*

**Why first.** The per-stimulus vs per-batch reinforcement decision (HLD §6 #2) must be made on
data, not argument. This phase adds **no behavioural change** — it only counts and persists,
riding the existing JPA path. Likely headline result: `AdrenergicStimulus` calls
`regulateAll(delta)` (touches every live drive), and `PartialAppraisal` emits adrenergic
stimuli periodically, so same-drive collisions within a batch may be common — but measure, don't
assume.

### Task 1.1 — Instrument `HomeostaticRegulation` to count regulating stimuli per batch

**Reference — current code** (`creature/components/HomeostaticRegulation.java`): the
`onReceive` loops over the batched `List`, and per stimulus takes a `before` snapshot, calls
`creature.emotions().regulate(...)` (or `regulateAll` for adrenergic), takes an `after`
snapshot, and persists an `InternalDynamicState(before, after, change)`. **Leave that body
exactly as-is** (behaviour and existing records unchanged); add only batch-level counters and
one persist at the end.

**New entity** (`creature/bd/RegulationBatchStat.java`) — same `PersistenceState` + JPA pattern
as `InternalDynamicState`:

```java
package br.cefetmg.lsi.l2l.creature.bd;

import javax.persistence.*;

@Entity
@Table(name = "regulation_batch_stat", schema = "data")
@NamedNativeQueries({
    @NamedNativeQuery(name = "RegulationBatchStat.countByRegulatingCount",
        query = "select rbs.regulating_count as c, count(*) as n " +
                "from data.regulation_batch_stat rbs " +
                "inner join data.change_stimulus_state css on rbs.changestimulusstate_id = css.id " +
                "where css.key = ? group by rbs.regulating_count order by c"),
    @NamedNativeQuery(name = "RegulationBatchStat.sameDriveCollisions",
        query = "select count(*) from data.regulation_batch_stat rbs " +
                "inner join data.change_stimulus_state css on rbs.changestimulusstate_id = css.id " +
                "where css.key = ? and rbs.same_drive_collision = true")
})
public class RegulationBatchStat implements PersistenceState {
    @Id @GeneratedValue private int id;
    private int batchSize;
    private int regulatingCount;
    private boolean sameDriveCollision;
    private int drivesTouchedMask;      // bit0=HUNGER, bit1=SLEEP, ... extend as dims go live

    @JoinColumn @OneToOne(cascade = {CascadeType.ALL})
    private ChangeStimulusState changeStimulusState;

    public RegulationBatchStat() { }
    public RegulationBatchStat(int batchSize, int regulatingCount, boolean sameDriveCollision,
                               int drivesTouchedMask, ChangeStimulusState changeStimulusState) {
        this.batchSize = batchSize; this.regulatingCount = regulatingCount;
        this.sameDriveCollision = sameDriveCollision; this.drivesTouchedMask = drivesTouchedMask;
        this.changeStimulusState = changeStimulusState;
    }
    public int getId() { return id; }
    public int getBatchSize() { return batchSize; }
    public int getRegulatingCount() { return regulatingCount; }
    public boolean isSameDriveCollision() { return sameDriveCollision; }
    public int getDrivesTouchedMask() { return drivesTouchedMask; }
    public ChangeStimulusState getChangeStimulusState() { return changeStimulusState; }
}
```

**Added lines in `HomeostaticRegulation.onReceive`** (existing body elided):

```java
@Override
public void onReceive(Object message) {
    List stimuli = (List) message;

    // --- [INSTRUMENTATION] ---
    int batchSize = stimuli.size();
    int regulatingCount = 0, hungerHits = 0, sleepHits = 0, drivesTouchedMask = 0;
    // -------------------------

    for (Object aStimuli : stimuli) {
        Stimulus stimulus = (Stimulus) aStimuli;
        /* ...existing before/regulate/emit/after/persist(InternalDynamicState)... */

        // --- [INSTRUMENTATION] ---
        if (stimulus instanceof NutritiveStimulus)      { regulatingCount++; hungerHits++; drivesTouchedMask |= 1; }
        else if (stimulus instanceof CholinergicStimulus){ regulatingCount++; sleepHits++;  drivesTouchedMask |= 2; }
        else if (stimulus instanceof AdrenergicStimulus){ regulatingCount++; drivesTouchedMask |= 1 | 2; } // regulateAll → all drives
        // -------------------------
    }

    // --- [INSTRUMENTATION] one record per batch ---
    boolean sameDriveCollision = (hungerHits >= 2) || (sleepHits >= 2);
    ChangeStimulusState batchChange = new ChangeStimulusStateBuilder(this, this.id).build(); // confirm builder method
    persist(batchChange, new RegulationBatchStat(batchSize, regulatingCount,
            sameDriveCollision, drivesTouchedMask, batchChange));
    // ----------------------------------------------
}
```

> **Agent notes:** (a) `ChangeStimulusStateBuilder` is used elsewhere as
> `.buildOneReceivedOneEmitted(received, emitted)`; confirm a timestamp-only builder method or
> reuse a minimal change record. (b) Match the existing schema-creation convention (Hibernate
> auto-DDL / Flyway) for the new table. (c) `AdrenergicStimulus` regulating *all* drives is the
> main collision source — count it, don't exclude it.

**Acceptance:** `data.regulation_batch_stat` populated by a baseline run; hunger-deprivation
-over-time plot is identical (within noise) to a pre-instrumentation run.

### Task 1.2 — Java extractor (`analysis/RegulationBatchExtractor.java`)
Follow the existing `analysis` convention (DB query → CSV; per README the package "executes the
database queries, extracting, organizing and writing the data in a CSV file").

```java
package br.cefetmg.lsi.l2l.analysis;

public class RegulationBatchExtractor {
    private final javax.persistence.EntityManager em;
    public RegulationBatchExtractor(javax.persistence.EntityManager em) { this.em = em; }

    public void extractCountHistogram(String creatureKey, java.io.Writer out) throws Exception {
        @SuppressWarnings("unchecked")
        java.util.List<Object[]> rows = em.createNamedQuery("RegulationBatchStat.countByRegulatingCount")
            .setParameter(1, creatureKey).getResultList();
        out.write("creatureKey,regulatingCount,batches\n");
        for (Object[] r : rows) out.write(creatureKey + "," + r[0] + "," + r[1] + "\n");
    }

    public void extractSameDriveCollisions(String creatureKey, java.io.Writer out) throws Exception {
        Object n = em.createNamedQuery("RegulationBatchStat.sameDriveCollisions")
            .setParameter(1, creatureKey).getSingleResult();
        out.write("creatureKey,sameDriveCollisions\n");
        out.write(creatureKey + "," + n + "\n");
    }
}
```
**Acceptance:** two CSVs per creature (`*_reg_hist.csv`, `*_reg_collisions.csv`).

### Task 1.3 — Python analysis script (`analysis/reg_granularity.py`)
Matches the `/analysis` convention (`wd` points at results dir). Prints the decisive numbers.

**Decision rule:** `p_collision` = fraction of regulating batches with `sameDriveCollision`.
`< ~1%` → **pure per-batch** reinforcement (simplest). `>= ~1%` → **frozen-baseline
per-stimulus**. The 1% is a starting dial; report the number, let the team set it.

```python
# analysis/reg_granularity.py  (Python 3 + pandas; confirm project interpreter — legacy is Py2.7)
import os, glob, pandas as pd, matplotlib; matplotlib.use("Agg")
import matplotlib.pyplot as plt

wd = "/path/to/simulation/results"     # set per convention
COLLISION_THRESHOLD = 0.01

hist = pd.concat([pd.read_csv(f) for f in glob.glob(os.path.join(wd, "*_reg_hist.csv"))],
                 ignore_index=True)
agg = hist.groupby("regulatingCount")["batches"].sum().sort_index()
regulating = agg[agg.index >= 1].sum()
multi = agg[agg.index >= 2].sum()
p_multi = float(multi)/regulating if regulating else 0.0

coll_files = glob.glob(os.path.join(wd, "*_reg_collisions.csv"))
total_coll = int(pd.concat([pd.read_csv(f) for f in coll_files])["sameDriveCollisions"].sum()) if coll_files else 0
p_coll = float(total_coll)/regulating if regulating else 0.0

print("Regulating batches: %d | multi(>=2): %d (p=%.4f) | same-drive collisions: %d (p=%.4f)"
      % (regulating, multi, p_multi, total_coll, p_coll))
print("DECISION:", "PURE PER-BATCH" if p_coll < COLLISION_THRESHOLD else "FROZEN-BASELINE PER-STIMULUS")

plt.bar(agg.index.astype(int), agg.values)
plt.xlabel("regulating stimuli per batch"); plt.ylabel("batches")
plt.title("Regulating-stimulus count per onReceive batch")
plt.savefig(os.path.join(wd, "reg_granularity_hist.png"), dpi=120, bbox_inches="tight")
```
**Acceptance:** prints the two fractions + DECISION; writes the histogram PNG.

### Task 1.4 — Record decision; parameterise as an enum
Write the measured `p_multi`/`p_collision` and the chosen mode into HLD §6 #2. Expose the choice
as a single enum `ReinforcementGranularity { PER_BATCH, PER_STIMULUS_FROZEN_BASELINE }` consumed
by the trace-reinforcement code (Task 4.2), so switching later is config, not a rewrite.

If `PER_STIMULUS_FROZEN_BASELINE`: the `HomeostaticRegulation` refactor is to take **one**
`before` snapshot at batch entry and compute each regulating stimulus's delta against that
frozen baseline (not the running mutated state). Deltas intentionally won't sum to the net batch
change when two stimuli hit one drive — correct, each cause measured against "how I felt
entering this moment."
**Acceptance:** enum exists and is consumed downstream; numbers recorded.

---

## Phase 2 — The Species Model (offline pre-training)

### Task 2.1 — Trajectory export
Export ID-keyed sequential interactions reconstructable into `(s_t, a_t, Δemotion)` tuples
(emotional-consequence form per HLD §5 — not full `s_{t+1}`). Stamp each row with the creature's
cognitive-cycle ordinal and `creatureKey`; reconstruct tuples by key, never by row order across
logs. Aggregate the existing `CreatureExtractor` + `ArousalHistoryExtractor` outputs. Include
exploratory/random-policy runs per Task 0.1.
**Acceptance:** dataset of cycle-keyed tuples; held-out validation split exists.

### Task 2.2 — PyTorch JEPA architecture
Small MLPs (hidden_dim ≈ 128): encoder `Enc(x)`, predictor `Pred(s,a)`, critic
`Crit(s,a) → 9 emotion dims`.
- **Bound the critic output** to `[MIN_AROUSAL_LEVEL, MAX_AROUSAL_LEVEL]` (final sigmoid +
  affine, or tanh). Not optional — Task 6.3's `exp(-cost)` misbehaves on unbounded inputs.
- **Individual adapter, not LoRA.** At this scale low-rank saves nothing. Keep the species base
  frozen; give each creature a small trainable additive adapter (e.g. 2-layer MLP added to the
  predictor output, or a fine-tunable final layer). Same intent as the v4 "LoRA" plan
  (shared base + private delta), far simpler.
**Acceptance:** forward pass shapes correct; critic output provably within arousal bounds.

### Task 2.3 — Offline base training (SIGReg) + collapse check
Train with the two-term LeWorldModel objective (next-latent prediction + SIGReg Gaussian
regulariser). **Verify no collapse** on the validation split (latent per-dim variance,
effective rank) — a collapsed model passes shape tests but is useless; make this a failing
acceptance condition. Export TorchScript (`species_encoder.pt`, `species_predictor.pt`,
`species_critic.pt`) **plus `model_contract.json`** (invariant #4) into
`src/main/resources/models/`.
**Acceptance:** TorchScript + contract present; variance/effective-rank report shows no collapse.

---

## Phase 3 — Memory hierarchy & engram assembly

> **Key code facts driving this phase:**
> `MemorySystemActor` is a **total stub** — `addShortTermMemory` is empty, `getMemories` returns
> an empty list. `ShortTermMemory` carries `(ActionType actionType, SequentialId id, Emotion
> emotion)` — final-fielded, `Serializable`, **no perception object, no cycle counter**.
> `FullAppraisal` already calls `memorySystem.addShortTermMemory(stm)` at decision time with
> `new ShortTermMemory(action.type, action.perception.id, emotional.maxEmotion)`, and
> `produceCortical` proves `s_t` is still local at commit (`Perception perception =
> action.perception`; `CorticalStimulus` built with `action.perception.id` as `target`).
> `Valuation` already does `getMemories(evaluation.objectId)` then `.filter(mem::equals)` — but
> because the store is a stub, that branch is permanently dead (Fig. 5 in the TCC shows the
> `MEMORY` and `SHORT_TERM_MEMORY` selection mechanisms flat at zero for the whole run). Making
> the store real is itself a latent bugfix to existing behaviour.

### Task 3.1 — Extend `ShortTermMemory` into an engram-capable record
Add (preserving immutability, invariant #2): the originating `Perception` (deep-copied or
immutable), the pre-action emotion snapshot (already passed as `emotional.maxEmotion`), and the
creature's `long cognitiveCycle`. Keep the existing 3-arg constructor for source compatibility,
or migrate call sites. This is the `(s_t, a_t, emotion@t, cycle)` record the trace lays down.
**Acceptance:** compiles; existing `FullAppraisal` and `Valuation` call sites updated; fields
immutable.

### Task 3.2 — Implement `MemorySystemActor` as a real store (replace the stub)
Back it with a bounded structure keyed for retrieval by `SequentialId` (so `Valuation`'s
existing `getMemories(evaluation.objectId)` finally returns matches and its dead branch comes
alive) **and** queryable as a recent-engram window for sleep consolidation (Task 5.2). Bounded
eviction by cognitive-cycle age (the architecture tolerates stimulus/record loss).
**Acceptance:** `getMemories` returns relevant records; a unit test shows `Valuation`'s
`correspondingMemories` is non-empty after a matching action+evaluation; store stays bounded.

### Task 3.3 — Cognitive-cycle counter on the creature
Add a per-creature `long` incremented once per `onReceive` pass in the components that pace the
creature (at minimum `FullAppraisal` / the emotional cycle). This is the trace clock (invariant
#1). It is **not** wall-clock and **not** global — it is this creature's count of its own
cognitive passes. Expose it where the trace is laid and reinforced.
**Acceptance:** counter is monotonic per creature, independent of other creatures and of machine
load; two creatures replaying identical experience sequences see identical counts.

---

## Phase 4 — Eligibility traces & credit assignment

> **Why this phase exists (HLD §3):** the current system assigns credit by *stimulus type* —
> `HomeostaticRegulation` hard-codes `ActionType.EAT` when a `NutritiveStimulus` arrives,
> because only an EAT can cause one. That works only for consummatory acts with dedicated
> pathways. The world model must learn long-horizon transitions whose rewarding signal cannot
> identify its own cause. Eligibility traces supply that missing link, on the creature's own
> cognitive clock.

### Task 4.1 — Eligibility-trace buffer (`creature/memory/`)
A per-creature buffer of warm tags `(perceptionId, actionType, emotion@t, layCycle)`. Decay is a
function of `currentCycle − layCycle` (Task 3.3), **never wall-clock**. Lay down a trace at the
existing `FullAppraisal.addShortTermMemory` call site — you are enriching a write that already
happens, not adding a new path. Keep the existing type-dedicated fast path (EAT/SLEEP →
`EvaluationStimulus`) untouched; it is a length-1 trace the world disambiguates for free.
**Acceptance:** traces lay down at decision time and decay by cycle count; fast-path EAT/SLEEP
valuation behaviour is unchanged.

### Task 4.2 — Route the emotional delta to warm traces (reinforcement)
`HomeostaticRegulation` already computes the reinforcement signal — the `before`/`after`
`EmotionalState` delta inside `InternalDynamicState`, currently persisted then discarded for
learning. Route that delta to the trace buffer, reinforcing each warm trace weighted by its
decayed eligibility, and emit a finished engram into `MemorySystem` (Task 3.2) when reinforcement
lands. Honour the granularity enum from Task 1.4 (`PER_BATCH` vs `PER_STIMULUS_FROZEN_BASELINE`).
**Acceptance:** a delayed reward reinforces a still-warm prior action (unit test with a
multi-cycle gap); engrams appear in the store with `(s_t, a_t, Δemotion)` populated.

### Task 4.3 *(future / optional)* — Cue competition
Add at least one *which*-cue mechanism to suppress spurious correlations (overshadowing via
salience-weighted lay-down is the cheapest; blocking leverages the existing Rescorla–Wagner-style
`OperantConditioning`). Defer until Task 4.2 produces engrams; note as known limitation until
then (HLD §3.5).
**Acceptance:** with a deliberately co-occurring irrelevant cue, its learned association stays
below that of the true cause.

---

## Phase 5 — DJL integration & sleep-gated consolidation

### Task 5.1 — Model service: one load per node, thread-safe inference
Load the heavy species `Model` once per JVM (a `MLServiceProvider` injected into creatures).
**Never share one DJL `Predictor` across creature actors** — `Predictor` is not thread-safe and
`WorldModelFilter` runs concurrently on `component-dispatcher`. Use a pool / `ThreadLocal` /
per-call predictors. Validate `model_contract.json` at load (invariant #4). All NDArray work
inside try-with-resources `NDManager`.
**Acceptance:** concurrent inference from many creatures on one node produces no corruption;
contract mismatch aborts at boot.

### Task 5.2 — `WorldModelActor`: sleep-gated, cancellable consolidation
On its own dispatcher (pattern of existing dedicated dispatchers like `bd-dispatcher`). Triggered
by sleep onset (existing `CholinergicStimulus` / `SLEEP`). Pulls a recent-engram window from
`MemorySystem` and runs a bounded number of adapter gradient steps (test-time-adaptation scale).
**Cooperative cancellation:** `CompletableFuture.cancel(true)` does **not** interrupt a running
`runAsync` body — use a shared `AtomicBoolean abortFlag` checked between batches; `handleWakeUp`
sets it; close the `NDManager` in `finally` (prevents native OOM). One consolidation per creature
at a time.
**Acceptance:** a wake mid-training exits within one batch boundary; native + heap memory return
to baseline (no NDArray leak).

### Task 5.3 — Circadian drive + anti-micro-nap hysteresis
Circadian sine modulates the sleep-drive *accumulation rate*. The anti-micro-nap property lives
in **explicit hysteresis**, not a comment: once `SLEEP` is entered, hold ≥ `MIN_SLEEP_TICKS`
(measured in cognitive cycles) regardless of how fast the drive is satisfied; wake only on dwell
minimum met or an exogenous interrupt (predator). Guarantees sleep contiguous enough to
consolidate.
**Acceptance:** logged sleep episodes are contiguous and meet the minimum; no micro-naps in a
baseline run.

---

## Phase 6 — Mode-2 deliberative action selection

### Task 6.1 — Populate the emotion vector the critic needs *(prerequisite)*
The species critic predicts 9 dims, but `EmotionalSystemActor` instantiates only 2
(`getMaxComplexArousal()` throws `not implemented yet`). Document which dims are live vs
constant-zero placeholders, and make the export (Task 2.1), the critic targets (Task 2.2), and
runtime agree. Otherwise the 9-D critic trains on noise columns.
**Acceptance:** a single emotion-dimension map shared by exporter, critic, and runtime.

### Task 6.2 — `WorldModelEngine.predictEmotionalCost`
Wrap species base + individual adapter. Squash/scale critic output into the arousal range
(ties to Task 2.2) before constructing any `EmotionalState`. Validate emotion index order
against `model_contract.json`, not a comment.
**Acceptance:** returns bounded `EmotionalState`; index order asserted against contract.

### Task 6.3 — `WorldModelFilter` (`creature/actionSelector/`): budgeted, optional, async-safe
Implements the existing `ActionFilter` interface so it slots into `ActionSelection`'s chain
beside `TargetDistanceFilter` / `RandomFilter`. CEM-style sampling over the **continuous** action
params already present in `CorticalStimulus` (`angle`, `focus`, `speed` are `double`) — no need
to touch the discrete `ActionType` enum. Score candidates by predicted emotional cost
(`cost = sum of aversive dims`, e.g. pain + fear; finite given Task 2.2 bounding).
- **Inference budget** (invariant #5): cap candidates × horizon; over budget → return input
  unmodified (Mode-1).
- **Confidence gating** (invariant #3 + Task 0.2): live prediction-error above threshold →
  self-disable for the cycle.
- **Thread-safe inference** via Task 5.1.
- **Mode-2 frequency gating:** don't run CEM every cycle — trigger on warranted deliberation
  (competing affordances / high arousal); cheap reactive path otherwise.
**Acceptance:** per-cycle latency within budget; disabling the model (or forcing low confidence)
reproduces baseline Mode-1 behaviour exactly; no concurrency errors under many creatures/node.

---

## Sequencing & parallelism

Phase 0 → Phase 1 (instrumentation, settles the granularity enum) → Task 6.1 (emotion map) →
Phase 3 + Phase 4 (memory + traces, fully testable with **no ML**) → Phase 2 (offline training,
parallel with Phases 3–4) → Phase 5 (DJL + sleep) → Phase 6 (filter, last).

Phases 3–4 (memory/traces) and Phase 2 (offline training) have no mutual dependency and run in
parallel. The behaviour-altering filter (Phase 6) is the final step — everything before it is
observable and testable while the creature behaves exactly as it does today. The single most
behaviour-relevant *pre-filter* change is Task 3.2 (real memory store), which revives the already
-present-but-dead memory-based action selection; validate its behavioural effect before layering
Mode-2 on top.

---

## Change log v5 → v6

- **Instrumentation promoted to Phase 1** with full entity/extractor/script snippets against real
  signatures; granularity decision now data-driven and parameterised as an enum.
- **Credit assignment reframed** (Phase 4): traces are not "generalising" the existing mechanism
  but supplying a link the architecture never represented; existing type-dedicated path explicitly
  preserved as a length-1 special case.
- **Eligibility-trace clock fixed** to per-creature cognitive-cycle count (Task 3.3), with the
  wall-clock option explicitly rejected (machine-load coupling).
- **Engram redefined** as emotional-consequence `(s_t, a_t, Δemotion, cycle)`, not full `s_{t+1}`
  (HLD §5); maps to LeCun's critic first, predictor deferred.
- **Memory tasks grounded in real code:** `MemorySystemActor` stub, `ShortTermMemory` signature,
  the dead `Valuation` branch, and the Fig.5 evidence that memory-based selection is currently
  inert — making Task 3.2 a behavioural bugfix as well as a feature.
- All v5 fixes retained (ID/ordering, immutability, LoRA→adapter, cancellation, thread-safety,
  inference budget, critic bounding, contract versioning, collapse check, graceful degradation).
