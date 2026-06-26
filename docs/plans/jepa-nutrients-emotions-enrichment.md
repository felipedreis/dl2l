# Plan: Nutrients & Emotions Enrichment

## Goal

Expand the world richness so creatures have more regulation opportunities, feeding
richer training signal into the JEPA world model.  Three independent sub-features:

1. **Rotten fruit** — eating it *increases* hunger instead of decreasing it.
2. **Cactus** — causes pain on passive collision (low) and deliberate EAT (high); permanent world object.
3. **Tedium regulation** — new `OBSERVE` action (deliberate stillness, increases tedium fast);
   `WANDER` relieves tedium; idle states accumulate tedium slowly.

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

No logic changes: the negative caloric value flows through the existing pipeline unchanged.

---

## 2. Cactus

### New object type — `PlantType`

A new enum (separate from `FruitType`; a cactus is not a fruit) implementing `WorldObjectType`:

```java
public enum PlantType implements WorldObjectType {
    CACTUS(0.3, 0.7, 12);   // passivePain, activePain, radius

    public final double passivePain;   // pain on passive collision
    public final double activePain;    // pain on deliberate EAT attempt
    public final double radius;
}
```

### New world object — `Plant`

Extends `WorldObject`. Responds to `DestructiveStimulus` with
`NociceptiveStimulus(activePain, ActionType.EAT, plantType)` back to sender (Mouth).
**Does NOT send its id to its parent** — the cactus is permanent and never removed.

### New stimulus — `NociceptiveStimulus`

```java
public class NociceptiveStimulus extends Stimulus {
    public final double painIntensity;
    public final ActionType action;      // null = passive; non-null = deliberate action
    public final WorldObjectType objectType;
}
```

### Pain signal flows

**Passive collision** (mouth shape intersects cactus — fired by CollisionDetectorActor):
```
CollisionDetector → MechanicalStimulus(objectType=CACTUS) → Mouth
Mouth: if (objectType instanceof PlantType)
  → NociceptiveStimulus(passivePain, action=null, CACTUS) → creature.homeostatic()
HomeostaticRegulation: regulate(PAIN, +passivePain)
  → NO EvaluationStimulus (rising pain itself drives avoidance on next cycle)
```

**Deliberate EAT** (creature selects EAT at distance=0):
```
FullAppraisal(EAT, CACTUS) → EffectorCortex → SomaticStimulus(EAT) → Mouth
→ DestructiveStimulus → Holder → Plant
→ NociceptiveStimulus(activePain, action=EAT, CACTUS) → Mouth
→ Mouth forwards NociceptiveStimulus → creature.homeostatic()
HomeostaticRegulation: regulate(PAIN, +activePain)
  → EvaluationStimulus(EAT, CACTUS, pain_emotion, +activePain) → Valuation
  → OperantConditioning penalises EAT on CACTUS
```

### Action set in FullAppraisal

| Condition | Offered actions |
|-----------|----------------|
| `FruitType`, `distance > 0` | APPROACH, AVOID, SLEEP, **OBSERVE** |
| `FruitType`, `distance == 0` | EAT, AVOID, SLEEP |
| `PlantType`, `distance > 0` | APPROACH, AVOID, SLEEP, **OBSERVE** |
| `PlantType`, `distance == 0` | EAT, AVOID, ESCAPE |
| No perception / Self | WANDER, SLEEP, **OBSERVE** |

OBSERVE is **only offered at `distance > 0`** (or when idle). The creature studies objects
from afar, then decides whether to approach — the deliberate planning chain.

### Holder routing

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

`WorldModelFilter.encodePerception` maps `FruitType` constants to one-hot slots; `PlantType`
and `ROTTEN_APPLE` encode as all-zeros (same as empty). `input_dim=6` and
`perception_feature_order` are left unchanged so the existing trained model is not broken.
New types will be added to the feature vector when the model is retrained with enriched data.

---

## 3. Tedium Regulation + OBSERVE Action

### New action — `OBSERVE`

Added to `ActionType` enum. Motor output in `produceCortical`:

```java
case OBSERVE:
    speed = 0;
    focus = Constants.MAX_VISION_FIELD_OPENING;   // full attentiveness
    angle = perception.angle;                      // face the object of interest
    break;
```

### New stimulus — `TediumStimulus`

```java
public class TediumStimulus extends Stimulus {
    public final double delta;          // positive = increase, negative = decrease
    public final ActionType action;     // for EvaluationStimulus routing
}
```

### Signal dispatch — FullAppraisal

After selecting an action each cognitive cycle, FullAppraisal sends `TediumStimulus`
to `creature.homeostatic()`:

| Action | delta | EvaluationStimulus? |
|--------|-------|---------------------|
| `WANDER` | `-TEDIUM_WANDER_RELIEF` (−5e-2) | Yes — reinforce WANDER when tedium falls |
| `OBSERVE` | `+TEDIUM_OBSERVE_RATE` (+5e-2) | Yes — penalise OBSERVE when tedium rises |
| `SLEEP` | (not sent) | — |
| All others | `+TEDIUM_IDLE_RATE` (+2e-2) | No — background accumulation, not a specific learning signal |

### HomeostaticRegulation handling

```java
if (stimulus instanceof TediumStimulus) {
    TediumStimulus tedium = (TediumStimulus) stimulus;
    Emotion regulated = creature.emotions().regulate(Constants.TEDIUM, tedium.delta);
    if (tedium.action == ActionType.WANDER || tedium.action == ActionType.OBSERVE) {
        emitted = new EvaluationStimulus(stimulus.origin, nextStimulusId(),
                id, Self.get(), tedium.action, regulated, tedium.delta);
        creature.valuation().tell(emitted, self());
    }
}
```

### Constants

```java
double TEDIUM_IDLE_RATE     = 2e-2;   // per cycle when not wandering/observing/sleeping
double TEDIUM_OBSERVE_RATE  = 5e-2;   // per OBSERVE cycle (deliberate stillness)
double TEDIUM_WANDER_RELIEF = 5e-2;   // per WANDER cycle
```

---

## model_contract.json updates

`live_emotion_dims` updated from `[0, 1]` to `[0, 1, 4, 5]` (hunger, sleep, pain, tedium
are now actively regulated). This field is metadata only; no Java code reads it at runtime.
The existing ML model predicts near-zero for dims 4 and 5 until retrained (consistent with
issue #28).

---

## Files Changed

| File | Action |
|------|--------|
| `world/FruitType.java` | Add `ROTTEN_APPLE(-0.3, 10)` |
| `world/PlantType.java` | **New** — `CACTUS` enum |
| `world/Plant.java` | **New** — permanent world object actor |
| `stimuli/NociceptiveStimulus.java` | **New** — passive and deliberate pain signal |
| `stimuli/TediumStimulus.java` | **New** — tedium regulation signal |
| `creature/common/ActionType.java` | Add `OBSERVE` |
| `common/Constants.java` | Add `TEDIUM_IDLE_RATE`, `TEDIUM_OBSERVE_RATE`, `TEDIUM_WANDER_RELIEF` |
| `creature/components/Mouth.java` | Handle `NociceptiveStimulus`; add passive pain on `MechanicalStimulus` from `PlantType` |
| `creature/components/HomeostaticRegulation.java` | Handle `NociceptiveStimulus`, `TediumStimulus` |
| `creature/components/FullAppraisal.java` | `definePossibleActions` for `PlantType` + `OBSERVE`; `produceCortical` for `OBSERVE`; dispatch `TediumStimulus` |
| `cluster/Holder.java` | `createWorldObject` handles `PlantType` |
| `src/main/resources/models/model_contract.json` | `live_emotion_dims: [0,1,4,5]` |
| `simulations/basic.conf` | Add `ROTTEN_APPLE`, `CACTUS` blocks |

---

## Acceptance Criteria

- `mvn package` compiles clean.
- Eating `ROTTEN_APPLE` → positive hunger delta in DB records.
- Collision with `CACTUS` → positive pain delta; Plant actor is NOT removed.
- Eating `CACTUS` → higher pain delta + EvaluationStimulus in DB records.
- Tedium rises during OBSERVE/idle cycles; falls after WANDER.
- Operant conditioning penalises ROTTEN_APPLE EAT and CACTUS EAT over time.
- OBSERVE only offered when `distance > 0` or no perception.
