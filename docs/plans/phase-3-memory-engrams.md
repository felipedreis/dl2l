# Phase 3 — Memory Hierarchy & Engram Assembly

**Epic:** [#5](https://github.com/felipedreis/dl2l/issues/5)  
**Tasks:** [#19](https://github.com/felipedreis/dl2l/issues/19) (3.1 ShortTermMemory), [#20](https://github.com/felipedreis/dl2l/issues/20) (3.2 MemorySystemActor), [#21](https://github.com/felipedreis/dl2l/issues/21) (3.3 cognitive-cycle counter)  
**Branch:** `features/memory-engrams`

---

## Overview

Phase 3 turns the memory subsystem from a stub into a real engram store — fully testable **with no ML**. The three tasks are tightly coupled (3.3 must precede 3.1's full-arg constructor update, which 3.2 depends on for correct records) but developed in one branch.

Key effect: when complete, `Valuation.correspondingMemories` will be non-empty for the first time, reviving the MEMORY/SHORT_TERM_MEMORY action-selection pathway that has been dead since inception.

---

## Akka immutability constraint

`ShortTermMemory` is passed as a message between actors (`FullAppraisal` → `MemorySystemActor` via the TypedActor proxy). **Mutable objects must never be shared across actor boundaries.** The existing `Emotion` class is mutable (has `setLevel`/`setName`). The existing code stores a raw reference, which is unsafe — if `EmotionalSystemActor` mutates the emotion level after the STM is created, the stored memory is silently corrupted.

Fix: convert `ShortTermMemory` to a **Java record** with a compact constructor that makes a defensive copy of `Emotion`. Java records guarantee all fields are final and provide correct `equals`/`hashCode`/`toString` automatically. New immutable value types in this phase use records by default.

---

## Prior decisions carried forward

- **Invariant #1:** No global clock; no wall-clock. The cycle counter is per-creature message-count.
- **Invariant #2:** Deep immutability for all messages. `Perception` is already all-final; `Emotion` requires a defensive copy.
- **Phase 1 result:** `PER_STIMULUS_FROZEN_BASELINE` granularity enum exists. Phase 3 does not touch reinforcement.

---

## Task 3.1 — Extend `ShortTermMemory` into an engram record

**File:** `creature/memory/ShortTermMemory.java`

Convert from a class to a Java record with 5 fields:

```java
package br.cefetmg.lsi.l2l.creature.memory;

import br.cefetmg.lsi.l2l.common.SequentialId;
import br.cefetmg.lsi.l2l.creature.common.ActionType;
import br.cefetmg.lsi.l2l.creature.common.Perception;
import br.cefetmg.lsi.l2l.creature.components.Emotion;

import java.io.Serializable;

public record ShortTermMemory(
        ActionType actionType,
        SequentialId id,
        Emotion emotion,           // pre-action dominant emotion snapshot — defensive copy made below
        Perception perception,     // s_t at decision time — Perception is already all-final
        long cognitiveCycle        // creature's own onReceive pass count (Task 3.3)
) implements Serializable {

    // Compact constructor: defensive copy of Emotion to prevent external mutation
    public ShortTermMemory {
        if (emotion != null) {
            Emotion copy = new Emotion(emotion.getName());
            copy.setLevel(emotion.getLevel());
            emotion = copy;
        }
    }
}
```

**Why no `equals`/`hashCode` override:** The record's auto-generated `equals` compares all fields. `Valuation`'s filter no longer relies on `ShortTermMemory.equals` (see Task 3.1 call-site changes below) — so the default is correct and we leave it.

### `FullAppraisal` call site update (tied to Task 3.3)

Replace `FullAppraisal.java:66` with the full 5-arg record constructor after the cycle counter is added:

```java
ShortTermMemory stm = new ShortTermMemory(
        action.type, action.perception.id, emotional.maxEmotion,
        action.perception, cognitiveCycle);
memorySystem.addShortTermMemory(stm);
```

### `Valuation` call site update

The existing code builds a probe `ShortTermMemory` and uses `mem::equals` to filter. With the record's all-field equality the probe would never match (different `perception`/`cognitiveCycle`). Replace with an explicit field comparison — clearer and correct:

```java
// BEFORE (dead branch — remove probe object)
ShortTermMemory mem = new ShortTermMemory(evaluation.executedAction, evaluation.objectId,
        evaluation.regulatedEmotion);
List correspondingMemories = memories.stream()
        .filter(mem::equals)
        .collect(Collectors.toList());

// AFTER (explicit filter — getMemories already scoped to evaluation.objectId)
List<ShortTermMemory> correspondingMemories = memories.stream()
        .filter(m -> m.actionType() == evaluation.executedAction)
        .collect(Collectors.toList());
```

### Acceptance
- Compiles clean.
- Record fields are all final; `Emotion` stored inside the record is a private copy.
- `Valuation` filter correctly matches on `actionType` for memories of the same object.

---

## Task 3.2 — Implement `MemorySystemActor` as a real store

### `MemorySystem` interface addition

Add one method for Phase 5 sleep-consolidation (Task 5.2); `MemorySystemActor` implements it:

```java
List<ShortTermMemory> getRecentEngrams(int windowSize);
```

### Store design

`MemorySystemActor` is a TypedActor called synchronously from sibling creature components — no locks needed. Two complementary structures:

```java
private static final int MAX_SIZE = 1000;

// insertion-ordered; drives eviction and recent-window queries
private final ArrayDeque<ShortTermMemory> all = new ArrayDeque<>();

// keyed by the perception/object SequentialId for O(1) retrieval by Valuation
private final HashMap<SequentialId, List<ShortTermMemory>> byId = new HashMap<>();
```

### `addShortTermMemory`

```java
@Override
public void addShortTermMemory(ShortTermMemory stm) {
    all.addLast(stm);
    byId.computeIfAbsent(stm.id(), k -> new ArrayList<>()).add(stm);

    if (all.size() > MAX_SIZE) {
        ShortTermMemory evicted = all.pollFirst();
        List<ShortTermMemory> bucket = byId.get(evicted.id());
        if (bucket != null) {
            bucket.remove(evicted);
            if (bucket.isEmpty()) byId.remove(evicted.id());
        }
    }
}
```

### `getMemories`

```java
@Override
public List<ShortTermMemory> getMemories(SequentialId id) {
    List<ShortTermMemory> bucket = byId.get(id);
    return bucket != null ? new ArrayList<>(bucket) : new ArrayList<>();
}
```

Returns a defensive copy so callers cannot mutate internal state.

### `getRecentEngrams`

```java
@Override
public List<ShortTermMemory> getRecentEngrams(int windowSize) {
    int skip = Math.max(0, all.size() - windowSize);
    return all.stream().skip(skip).collect(Collectors.toList());
}
```

### Acceptance
- `getMemories(id)` returns all stored engrams for that id.
- Store stays at MAX_SIZE; oldest record evicted on overflow.
- `getRecentEngrams(n)` returns the last n engrams by insertion order.

---

## Task 3.3 — Cognitive-cycle counter on the creature

**File:** `creature/components/FullAppraisal.java`

Add a field:

```java
private long cognitiveCycle = 0;
```

At the top of `onReceive`, before the stimulus loop:

```java
cognitiveCycle++;
```

The counter is naturally per-creature: each `FullAppraisal` instance belongs to exactly one creature. It is message-paced (one increment per `onReceive` call), independent of wall-clock or scheduler pressure.

### Acceptance
- Starts at 1 on the first cognitive cycle.
- Monotonically increasing for the creature's lifetime.
- Two creatures that each process 50 batches both reach `cognitiveCycle == 50` regardless of ordering.

---

## Unit tests

**New class:** `src/test/java/br/cefetmg/lsi/l2l/creature/memory/MemorySystemActorTest.java`

`MemorySystemActor` is a plain Java class; no Akka harness needed.

1. **`testGetMemoriesReturnsStoredEngram`** — add a `ShortTermMemory`, call `getMemories(id)`, assert non-empty and contains the record.
2. **`testValuationFilterFindsCorrespondingMemory`** — add an engram `(EAT, id1)`, filter the result list by `m.actionType() == EAT`, assert exactly one match.
3. **`testBoundedEviction`** — add `MAX_SIZE + 1` engrams with distinct ids, assert `getMemories(oldestId)` returns empty.

---

## Files changed / created

| File | Action |
|---|---|
| `creature/memory/ShortTermMemory.java` | Rewrite as Java record with 5 fields + compact constructor |
| `creature/memory/MemorySystem.java` | Add `getRecentEngrams(int)` |
| `creature/memory/MemorySystemActor.java` | Replace stub with bounded store |
| `creature/components/FullAppraisal.java` | Add `cognitiveCycle` counter; update STM creation to 5-arg record |
| `creature/components/Valuation.java` | Remove probe object; replace `filter(mem::equals)` with explicit `actionType` check |
| `src/test/.../memory/MemorySystemActorTest.java` | Create — 3 unit tests |

No database schema changes. No new JPA entities. No actor topology changes.

---

## Sequencing

1. Task 3.1 — record skeleton (no call-site change yet; `FullAppraisal` still uses a temporary 3-of-5 workaround or compile-breaks until 3.3 done).
2. Task 3.3 — cycle counter in `FullAppraisal`; complete the 5-arg call site.
3. Task 3.2 — real store + interface update; update `Valuation` filter.
4. Unit tests → `mvn test`.
5. `mvn package` clean.
6. Short Docker simulation: confirm `Valuation` log shows `memories > 0` after EAT events.
