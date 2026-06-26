# Plan: Nutrients & Emotions Enrichment

## Goal

Expand the world richness so creatures have more regulation opportunities, feeding
richer training signal into the JEPA world model.  Three independent sub-features:

1. **Rotten fruit** — eating it *increases* hunger instead of decreasing it.
2. **Cactus** — causes pain when touched or eaten; does not get consumed.
3. **Tedium regulation** — tedium rises while the creature is still; wander relieves it.

---

## 1. Rotten Fruit

### Mechanism
The existing pipeline already supports negative nutritive values:

```
Fruit(caloricValue)  →  EnergeticStimulus(nutritiveValue = caloricValue)
→ Mouth → NutritiveStimulus(nutritiveValue)
→ HomeostaticRegulation: regulate(HUNGER, -nutritiveValue)
```

If `caloricValue = -0.3`, then `regulate(HUNGER, +0.3)` → hunger rises.
`EvaluationStimulus.arousalVariation = -nutritiveValue = +0.3` → operant conditioning
penalises EAT on rotten fruit.

### Changes
| File | Change |
|------|--------|
| `world/FruitType.java` | Add `ROTTEN_APPLE(-0.3, 10)` |
| `simulations/basic.conf` | Add `ROTTEN_APPLE` world-object block |
| Baseline sim configs | Add `ROTTEN_APPLE` block |

No logic changes: the negative caloric value flows through the existing pipeline unchanged.

---

## 2. Cactus

### New object type — `PlantType`

A new enum (separate from `FruitType`; a cactus is not a fruit and `Fruit.java` casts
to `FruitType`) implementing `WorldObjectType`:

```java
public enum PlantType implements WorldObjectType {
    CACTUS(0.5, 12);        // painIntensity, radius

    public final double painIntensity;
    public final double radius;
}
```

### New world object — `Plant`

Extends `WorldObject`.  Responds to `DestructiveStimulus` with a
`NociceptiveStimulus(painIntensity, plantType)` sent back to the sender (Mouth).
**Does NOT send its id to its parent** — the cactus is permanent and does not self-destruct.

### New stimulus — `NociceptiveStimulus`

```java
public class NociceptiveStimulus extends Stimulus {
    public final double painIntensity;
    public final WorldObjectType objectType;
}
```

### Pain signal flow

```
EAT/TOUCH action → EffectorCortex → SomaticStimulus → Mouth
→ DestructiveStimulus → Holder → Plant(cactus)
→ NociceptiveStimulus → Mouth
→ Mouth forwards NociceptiveStimulus to homeostatic()
→ HomeostaticRegulation: regulate(PAIN, +painIntensity)
→ EvaluationStimulus(EAT/TOUCH, CACTUS, pain_emotion, +painIntensity)
→ Valuation → OperantConditioning (penalises action on cactus)
```

### Action set for PlantType

In `FullAppraisal.definePossibleActions`:

| Condition | Offered actions |
|-----------|----------------|
| `distance > 0` | APPROACH, AVOID, SLEEP |
| `distance == 0` | TOUCH, AVOID, ESCAPE |

`TOUCH` replaces `EAT` at distance=0 (you don't eat a cactus; you just touch it and get hurt).

### `TOUCH` in EffectorCortex

```java
// before
if(cortical.action == ActionType.PLAY || cortical.action == ActionType.EAT)

// after
if(cortical.action == ActionType.PLAY || cortical.action == ActionType.EAT
        || cortical.action == ActionType.TOUCH)
```

`produceCortical` for TOUCH: `speed = 0`, `angle = perception.angle` (same as EAT).

### Holder routing

Add `else if (type instanceof PlantType)` branch in `Holder.createWorldObject`:

```java
} else if (type instanceof PlantType) {
    ActorRef worldObject = context().actorOf(
            Plant.props(id, type, factory.nextPosition(), collisionDetector),
            "object-" + id.toString());
    worldObjects.put(id, worldObject);
    worldObjecttypes.put(id, type);
}
```

### ML model compatibility

`WorldModelFilter.encodePerception` maps known `FruitType` constants to one-hot slots.
New `PlantType` and `ROTTEN_APPLE` will encode as all-zeros (same as "empty/Self").
This degrades the model's type-specific scoring for new objects but does **not break**
the existing trained model: `input_dim=6` is unchanged.  The model will be retrained in a
future experiment that includes the new object types.  `model_contract.json`
`perception_feature_order` is therefore left unchanged.

---

## 3. Tedium Regulation

### New stimulus — `TediumStimulus`

```java
public class TediumStimulus extends Stimulus {
    public final double delta;        // positive = increase, negative = decrease
    public final ActionType action;   // what caused this change (for EvaluationStimulus)
}
```

### Signal dispatch (FullAppraisal)

After selecting an action each cognitive cycle, `FullAppraisal` sends a `TediumStimulus`
to `creature.homeostatic()`:

| Action | delta |
|--------|-------|
| `WANDER` | `-TEDIUM_WANDER_RELIEF` |
| `SLEEP` | 0 (not sent — sleep has its own drive) |
| All others (EAT, APPROACH, AVOID, …) | `+TEDIUM_IDLE_RATE` |

### HomeostaticRegulation handling

```java
if (stimulus instanceof TediumStimulus) {
    TediumStimulus tedium = (TediumStimulus) stimulus;
    if (tedium.delta == 0) { /* skip */ }
    else {
        Emotion regulated = creature.emotions().regulate(Constants.TEDIUM, tedium.delta);
        // Reinforce wander when it relieves tedium
        if (tedium.delta < 0) {
            emitted = new EvaluationStimulus(stimulus.origin, nextStimulusId(),
                    id, Self.get(), tedium.action, regulated, tedium.delta);
            creature.valuation().tell(emitted, self());
        }
    }
}
```

Tedium accumulation (positive delta) is intentionally **not** sent to `Valuation`:
the rising tedium level itself drives the creature toward WANDER on the next cycle,
analogous to how adenosinergic accumulation drives SLEEP.

### Constants

```java
double TEDIUM_IDLE_RATE    = 2e-2;   // per cognitive cycle when not wandering
double TEDIUM_WANDER_RELIEF = 5e-2;  // per WANDER cycle
```

---

## model_contract.json updates

`live_emotion_dims` is updated from `[0, 1]` to `[0, 1, 4, 5]` (hunger, sleep, **pain**, **tedium**
are now actively regulated).  This is metadata only; no Java code reads `liveEmotionDims`
at runtime.  The existing ML model will predict near-zero for dims 4 and 5 until retrained,
consistent with issue #28 (populate emotion vector).

---

## Files Changed

| File | Action |
|------|--------|
| `world/FruitType.java` | Add `ROTTEN_APPLE(-0.3, 10)` |
| `world/PlantType.java` | **New** — `CACTUS` enum |
| `world/Plant.java` | **New** — world object actor |
| `stimuli/NociceptiveStimulus.java` | **New** |
| `stimuli/TediumStimulus.java` | **New** |
| `common/Constants.java` | Add `TEDIUM_IDLE_RATE`, `TEDIUM_WANDER_RELIEF` |
| `creature/components/Mouth.java` | Handle `NociceptiveStimulus` |
| `creature/components/HomeostaticRegulation.java` | Handle `NociceptiveStimulus`, `TediumStimulus` |
| `creature/components/EffectorCortex.java` | Add `TOUCH` to `SomaticStimulus` trigger |
| `creature/components/FullAppraisal.java` | `definePossibleActions` for `PlantType`; `produceCortical` for `TOUCH`; dispatch `TediumStimulus` |
| `cluster/Holder.java` | `createWorldObject` handles `PlantType` |
| `src/main/resources/models/model_contract.json` | `live_emotion_dims: [0,1,4,5]` |
| `simulations/basic.conf` | Add `ROTTEN_APPLE`, `CACTUS` blocks |

---

## Acceptance Criteria

- `mvn package` compiles clean (no new warnings).
- A creature that eats a `ROTTEN_APPLE` shows a positive hunger delta in the
  `mouth_interactions_state` / `homeostatic_regulation` DB records.
- A creature that touches/eats a `CACTUS` shows a positive pain delta; the cactus
  actor is NOT removed from `worldObjects`.
- Tedium rises during non-wander cycles and falls after WANDER in the emotion DB records.
- Operant conditioning penalises ROTTEN_APPLE EAT and CACTUS TOUCH actions over time.
