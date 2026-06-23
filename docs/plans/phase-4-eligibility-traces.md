# Phase 4 — Eligibility Traces & Credit Assignment

**Epic:** [#6](https://github.com/felipedreis/dl2l/issues/6)  
**Tasks:** [#22](https://github.com/felipedreis/dl2l/issues/22) (4.1 Eligibility-trace buffer), [#23](https://github.com/felipedreis/dl2l/issues/23) (4.2 Route emotional delta to warm traces)  
**Branch:** `features/eligibility-traces`

---

## Overview

Phase 4 adds the credit-assignment link the architecture has never represented: a per-creature eligibility-trace buffer that bridges the temporal gap between an action decision and its rewarding consequence. When a reinforcing emotional change arrives, it reinforces whatever past-action traces are still warm, producing a completed **Engram** — the training unit for the JEPA world model in Phase 5.

The two invariants carried from earlier phases are strict:
- **No wall-clock decay.** Traces decay over `currentCycle − layCycle` cognitive cycles, never `System.nanoTime`.
- **Fast path untouched.** EAT/SLEEP → `EvaluationStimulus` → `OperantConditioning` is left unchanged. The eligibility-trace mechanism is additive and separate.

---

## Architecture before and after

**Before Phase 4:** `FullAppraisal` lays `ShortTermMemory` entries (action + perception + emotion snapshot) into `MemorySystemActor`. `HomeostaticRegulation` computes `before`/`after` emotional deltas, persists them to `InternalDynamicState`, then **discards them for learning**. No link exists between action decisions and their delayed consequences.

**After Phase 4:** Every STM write by `FullAppraisal` is also a trace-laying event. Every non-zero emotional delta in `HomeostaticRegulation` calls `reinforceWarmTraces`, walking all warm traces and producing `Engram` records weighted by decayed eligibility. Engrams accumulate in `MemorySystemActor` for future consumption by the world model.

---

## Prior decisions carried forward

- **Invariant #1:** No global clock; no wall-clock. Decay is cognitive-cycle count.
- **Invariant #2:** Deep immutability for all messages. New `Engram` record is immutable.
- **Phase 3 result:** `ShortTermMemory` record stores `(actionType, id, emotion@t, perception, cognitiveCycle)`. `MemorySystemActor` has a bounded `ArrayDeque<ShortTermMemory>` keyed by id.
- **Phase 1 result:** `ReinforcementGranularity` enum (`PER_BATCH`, `PER_STIMULUS_FROZEN_BASELINE`) exists.

---

## Task 4.1 — Eligibility-trace buffer

### New file: `creature/memory/Engram.java`

A completed, reinforced memory entry. Produced when a warm trace receives an emotional delta.

```java
package br.cefetmg.lsi.l2l.creature.memory;

import br.cefetmg.lsi.l2l.common.SequentialId;
import br.cefetmg.lsi.l2l.creature.common.ActionType;
import br.cefetmg.lsi.l2l.creature.common.Perception;
import br.cefetmg.lsi.l2l.creature.components.Emotion;

import java.io.Serializable;

public record Engram(
        ActionType actionType,       // a_t — what the creature decided
        SequentialId id,             // perceptionId at decision time
        Emotion emotionAtDecision,   // emotional state snapshot at lay time
        Perception perception,       // s_t — world state at decision time
        long layCycle,               // cognitive cycle when trace was laid
        double emotionDelta,         // Δemotion × eligibility — effective credit
        long reinforcedCycle         // cognitive cycle when reinforcement landed
) implements Serializable {

    public Engram {
        if (emotionAtDecision != null) {
            Emotion copy = new Emotion(emotionAtDecision.getName());
            copy.setLevel(emotionAtDecision.getLevel());
            emotionAtDecision = copy;
        }
    }
}
```

`emotionDelta` stores `rawDelta × eligibility` — the effective credit assigned to this trace. The gap (`reinforcedCycle − layCycle`) is recoverable for analysis.

### `Constants.java` additions

```java
// Eligibility trace: half-life in cognitive cycles
int TRACE_DECAY_HALF_LIFE = 5;

// Traces below this eligibility are considered cold and skipped
double MIN_TRACE_ELIGIBILITY = 0.01;   // ≈ 6.6 half-lives ≈ 33 cycles
```

### `MemorySystem.java` interface changes

Add three methods:

```java
// Reinforce all warm traces with the given emotional delta at currentCycle.
// For each warm trace, produces an Engram and adds it to the store.
void reinforceWarmTraces(double emotionDelta, long currentCycle);

// Store a completed engram (called internally by reinforceWarmTraces).
void addEngram(Engram engram);

// Return the last windowSize engrams in insertion order (for world model training).
List<Engram> getRecentEngrams(int windowSize);
```

`getRecentEngrams` return type changes from `List<ShortTermMemory>` to `List<Engram>`. No callers outside interface/actor exist yet (introduced in Phase 3 as a stub).

### `MemorySystemActor.java` changes

Add engram storage and the decay logic alongside the existing STM store:

```java
private static final int MAX_ENGRAM_SIZE = 1000;
private static final double LAMBDA = Math.log(2) / Constants.TRACE_DECAY_HALF_LIFE;

private final ArrayDeque<Engram> engrams = new ArrayDeque<>();
```

**`reinforceWarmTraces` implementation:**

```java
@Override
public void reinforceWarmTraces(double emotionDelta, long currentCycle) {
    for (ShortTermMemory trace : all) {
        long gap = currentCycle - trace.cognitiveCycle();
        if (gap < 0) continue;                // future trace — should not happen
        double eligibility = Math.exp(-LAMBDA * gap);
        if (eligibility < Constants.MIN_TRACE_ELIGIBILITY) continue;

        Engram engram = new Engram(
                trace.actionType(), trace.id(), trace.emotion(),
                trace.perception(), trace.cognitiveCycle(),
                emotionDelta * eligibility, currentCycle);
        addEngram(engram);
    }
}
```

**`addEngram` implementation:**

```java
@Override
public void addEngram(Engram engram) {
    engrams.addLast(engram);
    if (engrams.size() > MAX_ENGRAM_SIZE) {
        engrams.pollFirst();
    }
}
```

**`getRecentEngrams` update:**

```java
@Override
public List<Engram> getRecentEngrams(int windowSize) {
    int skip = Math.max(0, engrams.size() - windowSize);
    return engrams.stream().skip(skip).collect(Collectors.toList());
}
```

### Acceptance (4.1)
- Existing STM store and eviction logic unchanged.
- `reinforceWarmTraces` called with no warm traces produces no Engrams.
- A trace laid at cycle 1 has eligibility ≈ 1.0 when reinforced at cycle 1, ≈ 0.5 at cycle 6, ≈ 0.01 at cycle ~34.
- Engram store bounded at `MAX_ENGRAM_SIZE`; oldest evicted on overflow.

---

## Task 4.2 — Route emotional delta to warm traces

### `HomeostaticRegulation.java` changes

**Add fields:**

```java
private MemorySystem memorySystem;
private long cognitiveCycle = 0;
```

**`preStart` addition:**

```java
@Override
public void preStart() throws Exception {
    super.preStart();
    memorySystem = creature.memory();
}
```

**`onReceive` modification:**

Increment cycle counter at batch start. After each stimulus is processed and `before`/`after` are computed, compute the scalar emotional delta and call `reinforceWarmTraces`. Apply this for ALL stimulus types (AdrenergicStimulus, NutritiveStimulus, CholinergicStimulus) — the fast-path EvaluationStimulus for EAT/SLEEP is untouched and fires in parallel through `Valuation`.

```java
@Override
public void onReceive(Object message) {
    List stimuli = (List) message;
    cognitiveCycle++;

    // ... existing batch-stat counters ...

    for (Object aStimuli : stimuli) {
        Stimulus stimulus = (Stimulus) aStimuli;

        EmotionalState before = new EmotionalState();
        before.setHunger(creature.emotions().getLevel(Constants.HUNGER));
        before.setSleep(creature.emotions().getLevel(Constants.SLEEP));

        // ... existing stimulus processing (unchanged) ...

        EmotionalState after = new EmotionalState();
        after.setHunger(creature.emotions().getLevel(Constants.HUNGER));
        after.setSleep(creature.emotions().getLevel(Constants.SLEEP));

        // Route delta to warm traces (PER_STIMULUS_FROZEN_BASELINE)
        double delta = (after.getHunger() - before.getHunger())
                     + (after.getSleep()  - before.getSleep());
        if (delta != 0.0) {
            memorySystem.reinforceWarmTraces(delta, cognitiveCycle);
        }

        // ... existing persist calls (unchanged) ...
    }
    // ... existing batch-stat persist (unchanged) ...
}
```

The `delta` sign convention: negative = drives reduced = positive hedonic valence (same convention as `EvaluationStimulus.arousalVariation`). The caller of Engrams (Phase 5 world model) interprets sign appropriately.

**`ReinforcementGranularity` notes:**

The implementation above uses `PER_STIMULUS_FROZEN_BASELINE`: each stimulus uses the state immediately before it as its baseline. `PER_BATCH` would accumulate deltas and call `reinforceWarmTraces` once at the end of the loop. Both are correct; `PER_STIMULUS_FROZEN_BASELINE` is more principled for mixed-type batches (e.g. one `NutritiveStimulus` followed by one `AdrenergicStimulus`). Making this configurable via `simulations/*.conf` is deferred to a later phase.

### Acceptance (4.2)
- `HomeostaticRegulation` compiles and preStart resolves `memorySystem`.
- A `NutritiveStimulus` that reduces hunger by 0.5 calls `reinforceWarmTraces(-0.5, currentCycle)`.
- An `AdrenergicStimulus` with no net change in hunger + sleep (e.g. `delta == 0.0`) does NOT call `reinforceWarmTraces`.
- Fast-path `EvaluationStimulus` emission for `NutritiveStimulus` and `CholinergicStimulus` is unchanged.

---

## Unit tests

**File:** `src/test/java/br/cefetmg/lsi/l2l/creature/memory/MemorySystemActorTest.java`

Add to the existing test class (existing 4 tests remain):

### `testDelayedRewardReinforcesWarmTrace`

```
lay STM at cycle 1
call reinforceWarmTraces(delta=-0.5, currentCycle=3)
gap = 2; eligibility = exp(-ln2/5 * 2) ≈ 0.758
assert getRecentEngrams(10) has 1 entry
assert engram.actionType() == EAT
assert engram.emotionDelta() == approx(-0.5 * 0.758, 1e-6)
assert engram.layCycle() == 1
assert engram.reinforcedCycle() == 3
```

### `testColdTraceNotReinforced`

```
lay STM at cycle 1
call reinforceWarmTraces(delta=-0.5, currentCycle=100)
gap = 99; eligibility = exp(-ln2/5 * 99) ≈ 5e-7 < MIN_TRACE_ELIGIBILITY
assert getRecentEngrams(10) is empty
```

### `testMultipleWarmTracesAllReinforced`

```
lay STM A at cycle 1 (EAT)
lay STM B at cycle 2 (APPROACH)
call reinforceWarmTraces(delta=-0.3, currentCycle=3)
assert getRecentEngrams(10) has 2 entries (one per warm trace)
```

### `testZeroDeltaSkipsReinforcement` (in HomeostaticRegulation logic)

This is tested via `MemorySystemActor` directly: if caller never calls `reinforceWarmTraces` for a zero delta, the engram list stays empty. Document this assumption in the HomeostaticRegulation test (not a MemorySystemActor concern).

---

## Files changed / created

| File | Action |
|---|---|
| `creature/memory/Engram.java` | **Create** — new record with `(actionType, id, emotionAtDecision, perception, layCycle, emotionDelta, reinforcedCycle)` |
| `creature/memory/MemorySystem.java` | Add `reinforceWarmTraces`, `addEngram`, change `getRecentEngrams` return type to `List<Engram>` |
| `creature/memory/MemorySystemActor.java` | Add `engrams` deque, `LAMBDA` constant, implement 3 new methods |
| `creature/components/HomeostaticRegulation.java` | Add `memorySystem` field, `cognitiveCycle` counter, delta routing after each stimulus |
| `common/Constants.java` | Add `TRACE_DECAY_HALF_LIFE` and `MIN_TRACE_ELIGIBILITY` |
| `src/test/.../memory/MemorySystemActorTest.java` | Add 3 new test methods |

No database schema changes. No new JPA entities. No actor topology changes.

---

## Sequencing

1. Add `Engram.java` and constants (no wiring yet; compiles trivially).
2. Extend `MemorySystem` interface and implement in `MemorySystemActor`.
3. Update `getRecentEngrams` return type throughout.
4. Add unit tests — `mvn test` green.
5. Wire `HomeostaticRegulation`.
6. `mvn package` clean.
7. Short Docker run: confirm Engrams appear in log after creature eats (search for `reinforceWarmTraces` log line via `getRecentEngrams` size in HomeostaticRegulation).

---

## Exit criteria (from Epic #6)

- [ ] A delayed reward reinforces a still-warm prior action (multi-cycle gap unit test passes).
- [ ] Engrams appear in the store with `(s_t, a_t, Δemotion)` populated.
- [ ] Fast-path valuation (`OperantConditioning.varyProbability`) unchanged.
