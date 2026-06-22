# Phase 1 — Instrumentation: Settle Reinforcement Granularity

**Epic:** [#3](https://github.com/felipedreis/dl2l/issues/3)  
**Tasks:** #12, #13, #14, #15  
**Branch:** `features/coverage-probe` (or a new `features/phase-1-instrumentation`)

---

## Goal

Measure how often multiple regulating stimuli land in the same `HomeostaticRegulation.onReceive` batch and how often they hit the same drive (a "same-drive collision"). This data settles HLD §6 decision #2 — per-batch vs per-stimulus-frozen-baseline reinforcement — before any credit-assignment code is written.

**No behavioural change is made in this phase.** The existing `InternalDynamicState` records and hunger-deprivation curve are untouched.

---

## Background: why this matters

Inside one `onReceive` batch, the current loop mutates emotional state in place: each stimulus's `before` baseline already includes the effect of prior in-batch stimuli. If same-drive collisions are rare, a single batch-level delta is indistinguishable from per-stimulus deltas and the simpler per-batch scheme wins. If collisions are common (likely, given `AdrenergicStimulus.regulateAll` touches all drives), the third option — per-stimulus against a **frozen** batch-entry baseline — is needed for accurate credit assignment.

---

## Task 1.1 — Instrument `HomeostaticRegulation` + `RegulationBatchStat` entity

### New file: `creature/bd/RegulationBatchStat.java`

JPA entity following the same `PersistenceState` + `@NamedNativeQuery` pattern as `InternalDynamicState`. Fields:

| Field | Type | Meaning |
|---|---|---|
| `batchSize` | `int` | Total stimuli received in the batch |
| `regulatingCount` | `int` | Stimuli that are Nutritive, Cholinergic, or Adrenergic |
| `sameDriveCollision` | `boolean` | `hungerHits >= 2 \|\| sleepHits >= 2` |
| `drivesTouchedMask` | `int` | bit0=HUNGER, bit1=SLEEP; Adrenergic sets both |
| `changeStimulusState` | `@OneToOne ChangeStimulusState` | Creature key + timestamp for the JOIN |

Two `@NamedNativeQuery` entries:
- `RegulationBatchStat.countByRegulatingCount` — histogram of `regulating_count` values per creature key
- `RegulationBatchStat.sameDriveCollisions` — count of batches with `same_drive_collision = true` per creature key

Table: `data.regulation_batch_stat`. EclipseLink auto-DDL handles creation (no schema migration needed).

Also add the class to `src/main/resources/META-INF/persistence.xml`.

### Changes to `HomeostaticRegulation.onReceive`

The existing per-stimulus loop body (before/regulate/emitted/after/persist InternalDynamicState) is **left exactly as-is**. Additions only:

1. Before the loop: declare `batchSize`, `regulatingCount`, `hungerHits`, `sleepHits`, `drivesTouchedMask` (all int, zero-initialised).
2. At the end of each loop iteration, after `persist(change, dynamicState)`: classify the current stimulus and increment the appropriate counters (using `else if` since a stimulus is only one type).
3. After the loop: compute `sameDriveCollision`; build a batch-level `ChangeStimulusState` via `buildMultipleReceivedOneEmitted(new ArrayList<>(), null)` (empty received list — individual records are already captured per-stimulus); persist the `RegulationBatchStat`.

The empty-received `ChangeStimulusState` captures the creature key (`componentID`) and timestamp, which is all the named queries need for their JOIN.

New imports needed: `ArrayList`, `RegulationBatchStat`.

---

## Task 1.2 — Java extractors

Two `CreatureExtractor` subclasses in `analysis/extractor/`, both registered in `RoutineCreator.creatureRoutine`:

### `RegulationBatchHistExtractor`
- Runs `RegulationBatchStat.countByRegulatingCount` with `id.key`
- Returns `DataSet` with columns: `creatureKey`, `regulatingCount`, `batches`
- `getName()` → `id + "/reg_hist"` → saves as `<id>/reg_hist.csv`

### `RegulationBatchCollisionsExtractor`
- Runs `RegulationBatchStat.sameDriveCollisions` with `id.key`
- Returns `DataSet` with columns: `creatureKey`, `sameDriveCollisions`
- `getName()` → `id + "/reg_collisions"` → saves as `<id>/reg_collisions.csv`

Both follow the `BigInteger`/`Number` cast pattern used by native queries in PostgreSQL (consistent with how `PerceptionCoverageExtractor` handles raw result types).

`RoutineCreator.creatureRoutine` gets two new entries at the end of its `new Routine(...)` call.

---

## Task 1.3 — Python analysis: `analysis/reg_granularity.py`

Python 3 + pandas. Convention: set `wd` to the results directory.

Logic:
1. `glob` for `**/*reg_hist.csv` and concat
2. Group by `regulatingCount`, sum `batches`; compute `regulating` (batches with ≥1 regulating stimulus), `multi` (≥2), `p_multi`
3. `glob` for `**/*reg_collisions.csv` and sum `sameDriveCollisions`; compute `p_coll`
4. Print both fractions + `DECISION` string per the threshold
5. Save bar chart to `wd/reg_granularity_hist.png`

`COLLISION_THRESHOLD = 0.01` (1%) as a starting dial.

---

## Task 1.4 — `ReinforcementGranularity` enum + HLD update

New file: `src/main/java/br/cefetmg/lsi/l2l/creature/common/ReinforcementGranularity.java`

```java
public enum ReinforcementGranularity {
    PER_BATCH,
    PER_STIMULUS_FROZEN_BASELINE
}
```

Placed in `creature/common/` alongside `ActionType`. No constructor or fields — it is a pure selector consumed later by Task 4.2 trace-reinforcement code.

After running the simulation and the analysis script, update HLD §6 #2 with the measured `p_multi`, `p_coll`, and the chosen mode.

---

## Files changed

| File | Action |
|---|---|
| `src/main/java/.../creature/bd/RegulationBatchStat.java` | Create |
| `src/main/java/.../creature/components/HomeostaticRegulation.java` | Modify (counters + batch persist) |
| `src/main/resources/META-INF/persistence.xml` | Add `RegulationBatchStat` class entry |
| `src/main/java/.../analysis/extractor/RegulationBatchHistExtractor.java` | Create |
| `src/main/java/.../analysis/extractor/RegulationBatchCollisionsExtractor.java` | Create |
| `src/main/java/.../analysis/RoutineCreator.java` | Add two extractors to `creatureRoutine` |
| `analysis/reg_granularity.py` | Create |
| `src/main/java/.../creature/common/ReinforcementGranularity.java` | Create |
| `docs/hld/JEPA_WM_Integration.md` | Update §6 #2 with measured numbers (post-run) |

---

## Acceptance criteria

- `mvn package` compiles clean.
- A baseline simulation run populates `data.regulation_batch_stat`.
- The extractor run produces `*reg_hist.csv` and `*reg_collisions.csv` per creature.
- `reg_granularity.py` prints the two fractions + `DECISION` and writes `reg_granularity_hist.png`.
- The hunger-deprivation-over-time plot is identical (within noise) to a pre-instrumentation run.
- `ReinforcementGranularity` enum exists and compiles; HLD §6 #2 is updated with the measured decision.
