# Plan: Configurable Learning System (Issue #41)

## Context

Experiments require running simulations with and without the JEPA world model, memory
consolidation, and the circadian sleep drive. Currently these subsystems are always active.
This plan makes each independently toggleable via simulation config flags.

## Scope

Three toggles, all parsed from the `simulation.learningSettings` config block:

1. **Circadian cycle** — the `AdenosinergicStimulus` sleep drive emitted by `PartialAppraisal`
2. **Memory consolidation** — `SleepStarted` / `WakeUp` messages and the `MemoryConsolidator` actor
3. **Action filters** — ordered list of which `ActionFilter` implementations are active in `FullAppraisal`

Priority order of filters is always preserved; an enabled subset keeps the master order:
`TARGET_DISTANCE → AFFORDANCE → WORLD_MODEL → RANDOM`

## Files Changed

### New files

| File | Purpose |
|---|---|
| `cluster/settings/LearningSettings.java` | Immutable POJO with the three flags |
| `cluster/SimulationSettingsExtension.java` | Akka Extension distributing `LearningSettings` per-JVM |
| `src/test/java/.../actionSelector/ActionSelectionConfigTest.java` | Unit tests for filter toggling |
| `src/test/java/.../components/CircadianSettingsTest.java` | Unit tests for circadian toggle |

### Modified files

| File | Change |
|---|---|
| `cluster/settings/Simulation.java` | Parse `simulation.learningSettings` block with fallback defaults |
| `src/main/resources/simulation.conf` | Add `learningSettings` defaults block |
| `cluster/Holder.java` | Register `SimulationSettingsExtension` with parsed settings in `preStart()` |
| `creature/components/PartialAppraisal.java` | Skip `circadian.tick()` and `AdenosinergicStimulus` when disabled |
| `creature/components/FullAppraisal.java` | Build dynamic filter chain; skip `SleepStarted` when disabled |
| `creature/components/HomeostaticRegulation.java` | Skip `WakeUp` when consolidation disabled |
| `creature/CreatureActor.java` | Conditionally create `MemoryConsolidator`; null-safe `kill()` |
| `creature/actionSelector/ActionSelection.java` | Add `List<ActionFilter>` constructor |

## Config Schema

```hocon
simulation {
  learningSettings {
    circadianEnabled   = true
    consolidationEnabled = true
    # Ordered list of active filters; priority is the order listed.
    enabledFilters     = [TARGET_DISTANCE, AFFORDANCE, WORLD_MODEL, RANDOM]
  }
}
```

Omitting `learningSettings` entirely keeps the existing behaviour (all enabled, same order).

## Design Decisions

### Akka Extension for settings distribution

`PartialAppraisal` and `FullAppraisal` are created via a generic `Props.create(componentType, componentId)` loop that only passes a `SequentialId`. Changing this loop to pass additional parameters would require modifying every component constructor. Instead, we follow the existing `MLServiceExtension` pattern: store the settings in an Akka Extension keyed on the `ActorSystem`, initialize it in `Holder.preStart()` (which already has the `Simulation` object), and let each component read it in their own `preStart()`.

### WorldModelEngine is lazy-created

If `WORLD_MODEL` is absent from `enabledFilters`, `FullAppraisal` will not create `WorldModelEngine`, avoiding model loading overhead.

### Null-safe consolidator

When `consolidationEnabled = false`, `CreatureActor` does not create `MemoryConsolidator` (`consolidator` field stays null). `FullAppraisal` checks the flag before calling `creature.memoryConsolidator()`, and `kill()` guards with `if (consolidator != null)`.

### ActionSelection with List

Add `ActionSelection(List<ActionFilter>)` constructor so `FullAppraisal` can pass a dynamically-built filter list without varargs casting.

## Test Scenarios

1. All filters disabled except RANDOM → ActionSelection always uses RandomFilter.
2. WORLD_MODEL disabled but others enabled → WorldModelFilter absent from chain.
3. Filter priority preserved: enabling [AFFORDANCE, RANDOM] (not TARGET_DISTANCE or WORLD_MODEL) produces chain [AFFORDANCE → RANDOM].
4. `circadianEnabled = false` → no `AdenosinergicStimulus` emitted by PartialAppraisal per tick.
5. `consolidationEnabled = false` → `SleepStarted` is not sent even when action is SLEEP.
6. Default `LearningSettings()` → all enabled, master filter order preserved.
