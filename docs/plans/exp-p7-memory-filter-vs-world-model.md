# EXP-P7: Memory Filter vs. World Model — Implementation Plan

## Goal

Compare a classical symbolic memory filter (inspired by Suelen Mapa's 2009 long-term memory system)
against the JEPA neural world model for action selection, measuring impact on creature lifetime and
action quality. Results determine whether the neural world model adds measurable value over a
simpler associative memory approach.

## Background

### Suelen Mapa's Memory Filter (l2l 2009)

The original l2l `MemoryFilter` evoked long-term `QualiaExperience` records keyed by the current
complex emotion, then scored each candidate action by the maximum weighted expectancy found in those
records for the matching (action, objectType, basicEmotion) triple. Actions without matching memories
passed through unchanged. This is purely symbolic — no neural network required.

### dl2l data model mapping

| l2l concept        | dl2l equivalent                                       |
|--------------------|-------------------------------------------------------|
| QualiaExperience   | Engram (per decision, with eligibility decay)         |
| MacroExperience    | Engram.emotionDelta × eligibility                     |
| complexAffect key  | Engram.emotionAtDecision.getName() (dominant emotion) |
| memory evocation   | MemorySystem.getRecentEngrams(windowSize)              |
| expectancy         | -emotionDelta × eligibility (negative = good outcome) |

### Sign convention

`emotionDelta` in an Engram is `evaluation.arousalVariation` from `Valuation`:
- Negative = aversive emotion decreased → action was beneficial
- Positive = aversive emotion increased → action was harmful

The MemoryFilter score is `-emotionDelta × eligibility`; higher score = better expected outcome.

---

## Experiment Matrix (6 samples)

| Sample | Filters                              | Consolidation   | Purpose                                 |
|--------|--------------------------------------|-----------------|-----------------------------------------|
| P7-0   | distance, affordance, random         | none            | Preliminary: JEPA training data collect |
| P7-1   | distance, affordance, random         | none            | Official baseline (statistically sized) |
| P7-2   | distance, affordance, **memory**, random | none        | Memory filter alone                     |
| P7-3   | distance, affordance, **memory**, random | **memory**  | Memory filter + Mapa consolidation      |
| P7-4   | distance, affordance, **JEPA**, random   | none        | World model alone                       |
| P7-5   | distance, affordance, **JEPA**, random   | **adapter** | World model + adapter training          |

Metric: creature lifetime (cycles), action frequency distribution, engagement with food objects.

---

## Implementation Tasks

### Task 1 — MemoryFilter class

**File**: `src/main/java/br/cefetmg/lsi/l2l/creature/actionSelector/MemoryFilter.java`

Algorithm:
1. Retrieve `engrams = memory.getRecentEngrams(MEMORY_FILTER_WINDOW)` (constant = 256).
2. For each candidate action, compute `score = sum(-emotionDelta × eligibility)` for engrams
   matching the same `(ActionType, WorldObjectType)` pair. Use `perception.objectType.get()` to
   identify the target type; if undefined (WANDER/SLEEP/OBSERVE), group them as `Self`.
3. Build a scored list. Actions with no matching engrams go into an "unscored" bucket.
4. If the scored list is non-empty, return the single action with the highest score (consistent with
   WorldModelFilter's return-single-best behaviour so `ActionSelection` records `MEMORY` as the
   deciding filter).
5. If no action has any matching engram, return the input list unchanged (pass-through).

Gate: skip if `engrams.isEmpty()` or `actions.size() <= 1` (nothing to disambiguate).

**Unit test**: `MemoryFilterTest` in the test tree.
- Scenario A: empty engrams → returns all actions unchanged.
- Scenario B: engrams for one action only → returns that action.
- Scenario C: engrams for two actions; one has lower -emotionDelta (worse) → selects the other.
- Scenario D: action with no engrams → included in unscored; only scored action wins.

### Task 2 — Wire MemoryFilter into LearningSettings + FullAppraisal

**LearningSettings.java**: add `MEMORY` to `MASTER_FILTER_ORDER` between `AFFORDANCE` and
`WORLD_MODEL`:
```
TARGET_DISTANCE → AFFORDANCE → MEMORY → WORLD_MODEL → RANDOM
```

**FullAppraisal.java**: add `case MEMORY -> filterList.add(new MemoryFilter(memorySystem));`
in the filter construction switch.

### Task 3 — MemoryTraceConsolidator (parallel actor, Mapa mode)

**Do NOT modify `MemoryConsolidator`**. Create a separate actor:

**File**: `src/main/java/br/cefetmg/lsi/l2l/creature/ml/MemoryTraceConsolidator.java`

Receives `SleepStarted` / `WakeUp` (same messages as `MemoryConsolidator`). On sleep onset:

1. Retrieve `engrams = memory.getRecentEngrams(CONSOLIDATION_WINDOW)`.
2. Group by `(ActionType, WorldObjectType)`.
3. For groups where `mean(-emotionDelta) > MEMORY_CONSOLIDATION_THRESHOLD` (= 0.1):
   - Create one "consolidated" `Engram` per group with:
     - `eligibility = 1.0`
     - `emotionDelta = mean(emotionDelta)` across the group
     - `layCycle = currentCycle`, `reinforcedCycle = currentCycle`
   - Add to `MemorySystem` via `memory.addEngram(consolidated)`.
4. Persist a `MemoryConsolidationStat` JPA entity (creatureKey, onsetCycle, groupsConsolidated).

Does not load any DJL models — no ML dependency.

**Wiring in `CreatureActor`**: choose consolidation actor at construction time:
```java
if (effective.isConsolidationEnabled()) {
    boolean jepaMode = effective.isFilterEnabled(ActionSelectionType.WORLD_MODEL);
    consolidator = jepaMode
        ? context.actorOf(Props.create(MemoryConsolidator.class, ...), "memoryConsolidator")
        : context.actorOf(Props.create(MemoryTraceConsolidator.class, ...), "memoryConsolidator");
}
```

Both actors register under the same `"memoryConsolidator"` path so the rest of the codebase
(FullAppraisal, HomeostaticRegulation) sends to `creature.memoryConsolidator()` without change.

**Unit test**: `MemoryTraceConsolidatorTest` — verify that on `SleepStarted`, engrams for
consistently-beneficial (action, objectType) pairs produce consolidated engrams with
`eligibility = 1.0` in the memory system.

### Task 4 — Simulation configs (6 files)

```
simulations/exp_p7_0_jepa_train.conf          # P7-0: training data collection
simulations/exp_p7_1_baseline.conf             # P7-1: official baseline
simulations/exp_p7_2_memory_only.conf          # P7-2: memory filter, no consolidation
simulations/exp_p7_3_memory_consolidation.conf # P7-3: memory filter + Mapa consolidation
simulations/exp_p7_4_jepa_only.conf            # P7-4: JEPA filter, no consolidation
simulations/exp_p7_5_jepa_consolidation.conf   # P7-5: JEPA filter + adapter training
```

All configs: 1 holder, 10 creatures, reposition=true, 3× food density (855 apples — matching
EXP-51 training setup), RandomPositionFactory.

### Task 5 — Docker Compose files (6 files)

One compose file per sample, referencing the corresponding simulation config.

Local execution: `docker compose -f docker/docker-compose-exp-p7-N.yml up`.

### Task 6 — Experiment runner script

`scripts/run_exp_p7.sh`:
- **Phase 0**: collect training data (P7-0), run JEPA training pipeline (same as run_exp51.sh
  Phase 1+2), export model, rebuild jar + Docker image.
- **Phase 1**: run P7-1 through P7-5 samples with the appropriate number of trials.
- **Phase 2**: extract data from each sample.
- **Phase 3**: statistical analysis and report.

### Task 7 — Analysis script and report

`analysis/exp_p7_memory_vs_wm.py`:
- Load all 6 data sets.
- Primary metric: lifetime in cycles (ChosenActionState records, count per creature).
- Secondary metric: fraction of decisions using MEMORY / WORLD_MODEL filter (filter effectiveness).
- Statistical tests: Kruskal-Wallis across all groups (H, p-value), pairwise Mann-Whitney U with
  Bonferroni correction.
- Sample size for P7-1: computed from P7-0 data via power analysis (Cohen's d, α=0.05, β=0.20,
  two-tailed; will be computed at the end of Phase 0 in the runner script).

Report: `docs/reports/EXP_P7_MEMORY_FILTER_VS_WORLD_MODEL.md`.

---

## Implementation Order

1. MemoryFilter + test (Task 1)
2. LearningSettings + FullAppraisal wiring (Task 2)
3. MemoryConsolidator memory mode + test (Task 3)
4. Compile verification: `mvn package`
5. Simulation configs + Docker compose files (Tasks 4+5)
6. Runner script (Task 6)
7. Run experiments
8. Analysis + report (Task 7)

---

## Notes on the cluster

The RPI cluster (node-[0-3] at 192.168.1.20[0-3]) was unreachable at plan time. All experiments
will run locally using Docker unless the cluster becomes available. The runner script will check
connectivity first and fall back automatically.

---

## Key constants to add

```java
// MemoryFilter
int MEMORY_FILTER_WINDOW = 256;   // engrams to inspect

// MemoryConsolidator memory mode
double MEMORY_CONSOLIDATION_THRESHOLD = 0.1;  // min mean(-emotionDelta) to consolidate
```
