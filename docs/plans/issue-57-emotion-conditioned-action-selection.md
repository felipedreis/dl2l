# Issue #57 — Emotion-conditioned action selection: learned tendencies + optional innate bias

## Context

Issue #57 (roadmap Finding C, Task 3): the dominant emotion (`toRegulate`) is threaded through every
action filter but **no filter reads it**. Three gaps vs Campos (2006) and Mapa (2009):

1. `OperantConditioningActor` keys by `(target, action)` — emotion-blind.
2. `MemoryFilter` scores engrams by `(action, objectType)` — ignores drive.
3. No `ActionTendency` coarse prior (optional, default-off).

The fix is **not** a hardcoded policy. Tendencies are learned (operant + memory), with an optional
innate ActionTendency coarse bias shipped disabled by default.

---

## Files to change

| File | What changes |
|---|---|
| `creature/bd/ActionSelectionType.java` | Add `ACTION_TENDENCY` |
| `creature/conditioning/OperantConditioning.java` | Add `affectBasic` param to both methods |
| `creature/conditioning/ProbabilityBasedExperience.java` | Add `affectBasic` field |
| `creature/conditioning/OperantConditioningActor.java` | Key by `(affectBasic, target)`; lazy init |
| `creature/actionSelector/ActionProbabilityFilter.java` | Pass `toRegulate.getName()` to `getProbabilities` |
| `creature/components/Valuation.java` | Pass `regulatedEmotion.getName()` to `varyProbability` |
| `creature/actionSelector/MemoryFilter.java` | Prefer drive-matching engrams in scoring |
| `creature/actionSelector/ActionTendencyFilter.java` | **New** — innate coarse bias filter |
| `cluster/settings/LearningSettings.java` | Add `actionTendencyEnabled`, `actionTendencyMap` |
| `cluster/settings/Simulation.java` | Parse new `learningSettings` fields |
| `cluster/settings/LearningSettings.java` | Add `ACTION_TENDENCY` to `MASTER_FILTER_ORDER` |
| `creature/components/FullAppraisal.java` | Wire `ActionTendencyFilter` when enabled |

New test files:
- `creature/conditioning/OperantConditioningActorTest.java`
- `creature/actionSelector/ActionTendencyFilterTest.java`

---

## Step-by-step implementation

### Step 1 — `ActionSelectionType`: add `ACTION_TENDENCY`

```java
public enum ActionSelectionType {
    TARGET_DISTANCE, AFFORDANCE, MEMORY, RANDOM, SHORT_TERM_MEMORY, WORLD_MODEL, ACTION_TENDENCY
}
```

---

### Step 2 — `OperantConditioning` interface: add `affectBasic`

```java
public interface OperantConditioning {
    void varyProbability(String affectBasic, WorldObjectType target, ActionType action,
                         double delta, boolean valence);
    Optional<List<ActionProbability>> getProbabilities(String affectBasic, WorldObjectType target);
}
```

---

### Step 3 — `ProbabilityBasedExperience`: add `affectBasic` field

Add `public final String affectBasic;` alongside the existing `target` field. Update both
constructors to accept `String affectBasic` as the first parameter. Example:

```java
public ProbabilityBasedExperience(String affectBasic, WorldObjectType target) {
    this.affectBasic = affectBasic;
    this.target = target;
    // ... existing probability initialization ...
}
```

---

### Step 4 — `OperantConditioningActor`: key by `(affectBasic, target)` with lazy init

**Current**: pre-initializes experiences for all `FruitType` and `PlantType`.

**New**: lazy initialization — create a `ProbabilityBasedExperience` on first encounter of an
`(affectBasic, target)` pair. Use a helper that finds by both fields.

Key change in `varyProbability` and `getProbabilities`: replace the single-field `.filter(e ->
e.target.equals(target))` with `.filter(e -> e.affectBasic.equals(affectBasic) &&
e.target.equals(target))`. When no entry is found in `getProbabilities`, return `Optional.empty()`
(existing behavior). When no entry is found in `varyProbability`, create a new
`ProbabilityBasedExperience(affectBasic, target)` and add it to `experiences` before updating.

**Lazy init in `varyProbability`:**
```java
Optional<ProbabilityBasedExperience> found = experiences.stream()
        .filter(e -> e.affectBasic.equals(affectBasic) && e.target.equals(target))
        .findAny();
ProbabilityBasedExperience experience;
if (found.isEmpty()) {
    experience = new ProbabilityBasedExperience(affectBasic, target);
    experiences.add(experience);
} else {
    experience = found.get();
}
```

Remove the constructor's pre-initialization loop (no longer needed).

---

### Step 5 — `ActionProbabilityFilter`: pass `toRegulate` to `getProbabilities`

In `filter(actions, toRegulate)`, change:
```java
Optional<List<ActionProbability>> actionsProbabilityOp =
        operantConditioning.getProbabilities(target);
```
to:
```java
String affectBasic = toRegulate != null ? toRegulate.getName() : "";
Optional<List<ActionProbability>> actionsProbabilityOp =
        operantConditioning.getProbabilities(affectBasic, target);
```

When `toRegulate` is null (defensive), fall back to empty string — `getProbabilities` returns empty
→ the filter passes through all actions for that target (existing fallback behavior).

---

### Step 6 — `Valuation`: pass `regulatedEmotion` to `varyProbability`

`EvaluationStimulus` already carries `regulatedEmotion`. Change:
```java
operantConditioning.varyProbability(evaluation.type, evaluation.executedAction, 1, valence);
```
to:
```java
String affectBasic = evaluation.regulatedEmotion != null
        ? evaluation.regulatedEmotion.getName() : "";
operantConditioning.varyProbability(affectBasic, evaluation.type, evaluation.executedAction, 1, valence);
```

---

### Step 7 — `MemoryFilter`: prefer drive-matching engrams

**Current scoring**: for each engram, compute `score = -emotionDelta × eligibility` regardless of
which emotion was active when the engram was laid.

**New scoring**: run the existing algorithm **twice** — first pass over only drive-matching engrams
(where `engram.emotionAtDecision().getName().equals(toRegulate.getName())`). If that produces at
least one non-zero scored candidate, use that result. Otherwise fall back to the full engram set
(preserving the existing path).

Concretely, extract a helper method `computeScores(List<Engram>, List<Action>)` that returns
`Optional<List<Action>>` (empty = no scored candidates). Call it first with filtered engrams, then
with all engrams if needed.

Drive-matching logic:
```java
boolean matchesDrive(Engram e, Emotion toRegulate) {
    return toRegulate != null
        && e.emotionAtDecision() != null
        && e.emotionAtDecision().getName().equals(toRegulate.getName());
}
```

**Behavior**: if any engrams exist for the current drive, select from those. Only cross-drive engrams
are used as fallback — never starves the pipeline.

---

### Step 8 — `ActionTendencyFilter` (new class, optional innate bias)

```
package: br.cefetmg.lsi.l2l.creature.actionSelector
```

```java
public class ActionTendencyFilter implements ActionFilter {
    private final Map<String, Set<ActionType>> tendencyMap;

    public ActionTendencyFilter(Map<String, Set<ActionType>> tendencyMap) {
        this.tendencyMap = tendencyMap;
    }

    @Override
    public List<Action> filter(List<Action> actions, Emotion toRegulate) {
        if (toRegulate == null) return actions;
        Set<ActionType> tendency = tendencyMap.get(toRegulate.getName());
        if (tendency == null || tendency.isEmpty()) return actions;  // pass-through

        List<Action> intersection = actions.stream()
                .filter(a -> tendency.contains(a.type))
                .collect(Collectors.toList());

        return intersection.isEmpty() ? actions : intersection;  // never starves
    }

    @Override
    public ActionSelectionType getFilterType() {
        return ActionSelectionType.ACTION_TENDENCY;
    }
}
```

Default map per Campos (2006):
- `hunger → {EAT, APPROACH, WANDER}`
- `sleep  → {SLEEP, WANDER}`
- `pain   → {ESCAPE, AVOID, WANDER}`
- `tedium → {WANDER, OBSERVE}`

---

### Step 9 — `LearningSettings`: add `actionTendencyEnabled` + `actionTendencyMap`

Add two new fields:
- `boolean actionTendencyEnabled` (default `false`)
- `Map<String, Set<ActionType>> actionTendencyMap` (default: Campos 2006 map above)

Add constructor overload and getters. Update the `MASTER_FILTER_ORDER` to prepend `ACTION_TENDENCY`
at position 0 (it runs first as a coarse filter, before `TARGET_DISTANCE`):

```java
public static final List<ActionSelectionType> MASTER_FILTER_ORDER = List.of(
        ActionSelectionType.ACTION_TENDENCY,
        ActionSelectionType.TARGET_DISTANCE,
        ActionSelectionType.AFFORDANCE,
        ActionSelectionType.MEMORY,
        ActionSelectionType.WORLD_MODEL,
        ActionSelectionType.RANDOM
);
```

Default constructor: `actionTendencyEnabled = false`, `actionTendencyMap` = Campos 2006 defaults.

The `isFilterEnabled` method: for `ACTION_TENDENCY`, additionally check `actionTendencyEnabled`. Or
simpler: only include `ACTION_TENDENCY` in `enabledFilters` when it is enabled. When parsing from
config, if `actionTendencyEnabled = true`, add `ACTION_TENDENCY` to the front of `enabledFilters`.

---

### Step 10 — `Simulation.parseLearningSettings`: parse new fields

Extend the existing parser to read:
```hocon
learningSettings {
  actionTendencyEnabled = false           # optional, default false
  actionTendencyMap {                     # optional; if absent, use Campos 2006 defaults
    hunger = [EAT, APPROACH, WANDER]
    sleep  = [SLEEP, WANDER]
    pain   = [ESCAPE, AVOID, WANDER]
    tedium = [WANDER, OBSERVE]
  }
}
```

If `actionTendencyEnabled = true`, prepend `ACTION_TENDENCY` to the resolved filter list.

---

### Step 11 — `FullAppraisal.preStart`: wire `ActionTendencyFilter`

Add a case for `ACTION_TENDENCY` in the switch:
```java
case ACTION_TENDENCY -> filterList.add(
    new ActionTendencyFilter(learningSettings.getActionTendencyMap()));
```

---

## Unit tests

### `OperantConditioningActorTest` (new)

1. **Divergent learning per drive**: reinforce `(hunger, RED_APPLE, EAT)` 10×. Verify that
   `getProbabilities("hunger", RED_APPLE)` shows EAT probability raised, and
   `getProbabilities("sleep", RED_APPLE)` either returns empty (no experience yet) or a fresh
   uniform distribution (not raised) — confirming cross-drive isolation.

2. **Lazy init**: call `varyProbability` for a previously unseen `(affectBasic, target)` pair —
   must not throw, must create a new entry with the reinforced action raised.

### `ActionTendencyFilterTest` (new)

1. **Intersection non-empty**: candidate list `{APPROACH, AVOID, SLEEP}` with `hunger` tendency
   `{EAT, APPROACH, WANDER}` → result is `{APPROACH}`.

2. **Empty intersection pass-through**: candidate list `{EAT}` with `sleep` tendency `{SLEEP, WANDER}`
   → intersection is empty → return original `{EAT}` unchanged (no starvation).

3. **Null toRegulate**: returns full list unchanged.

4. **Unknown emotion**: emotion name not in tendencyMap → returns full list unchanged.

### `MemoryFilterTest` additions (extend existing test)

5. **Drive-matching preferred over cross-drive**: two engrams both for `(APPROACH, RED_APPLE)` — one
   laid under `hunger` (emotionDelta=-2, eligibility=1.0) and one under `sleep`
   (emotionDelta=-5, eligibility=1.0). With `toRegulate = hunger`, the filter must prefer the
   hunger-matching engram and return `APPROACH`; the sleep engram should not override it even though
   it has a larger raw score.

   More concretely: candidates = `{APPROACH RED_APPLE, APPROACH GREEN_APPLE}`. Hunger engram for RED
   has score +2; sleep engram for GREEN has score +5 but drive doesn't match. Filter with `hunger`
   should pick RED (drive-matched wins over higher cross-drive score).

---

## Validation experiment (mini-experiment)

Hypothesis: emotion-conditioned learning shifts action-selection-criteria frequencies toward
Campos/2015 Fig 4 profile (`Affordances ≈ 42%, Memory ≈ 41%, Nearest ≈ 15%, Random ≈ 1%`) without
reducing mean lifetime.

Arms:
- **Control** (`exp_p57_baseline.conf`): emotion-blind AFFORDANCE + MEMORY (current behavior)
- **Treatment** (`exp_p57_emotion_cond.conf`): emotion-conditioned AFFORDANCE + MEMORY

Configuration: 5 creatures, 1 node, circadian on, consolidation off (to isolate operant + memory).
`n = 50` realisations per arm per roadmap §6 guidance.

Docker compose files: `docker/docker-compose-exp-p57-baseline.yml` and
`docker/docker-compose-exp-p57-emotion-cond.yml`.

Report to `docs/reports/issue-57-emotion-conditioned-action-selection.md`.

---

## Acceptance criteria checklist

- [ ] Unit test: operant conditioning learns divergent probabilities per drive (HUNGER vs SLEEP) for
  same `(target, action)`.
- [ ] Unit test (innate bias, empty intersection): candidate list returned unchanged — no starvation.
- [ ] `MemoryFilter` preferentially selects engrams matching the current dominant drive.
- [ ] Mini-experiment shows action-criteria frequencies shift toward paper Fig 4 profile without
  reducing mean lifetime.
- [ ] `mvn package` clean; all existing tests pass.
- [ ] `ActionTendencyFilter` ships disabled by default; experiment configs show it can be enabled.
