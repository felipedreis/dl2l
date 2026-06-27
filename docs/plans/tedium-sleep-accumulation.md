# Plan: Tedium Accumulation During Sleep

## Context

PR #39 ("Nutrients & Emotions Enrichment") implemented rotten fruit, cactus/aloe, and tedium
regulation.  The tedium dispatch table in that plan explicitly excluded SLEEP:

| Action  | delta              | EvaluationStimulus? |
|---------|--------------------|---------------------|
| WANDER  | −TEDIUM_WANDER_RELIEF (−5e-2) | Yes |
| OBSERVE | +TEDIUM_OBSERVE_RATE  (+5e-2) | Yes |
| **SLEEP** | *(not sent)*     | — |
| Others  | +TEDIUM_IDLE_RATE (+2e-2)     | No |

The missing behaviour: when a creature is **still** (sleeping), tedium should accumulate
slowly.  Without this, long sleep episodes produce no tedium signal, so the creature has no
intrinsic motivation to wake up and explore.

## What is already done (no changes needed)

| Feature | Status |
|---------|--------|
| `ROTTEN_APPLE` in `FruitType` (caloricValue = −0.3) | Done |
| Rotten apple increases hunger via `regulate(HUNGER, −nutritiveValue)` | Done |
| `CACTUS` / `ALOE` in `PlantType` | Done |
| Passive pain on touch, active pain on EAT, healing on ALOE EAT | Done |
| `TEDIUM` emotion in `EmotionalSystemActor` (index 5) | Done |
| WANDER relieves tedium (−5e-2) | Done |
| OBSERVE accumulates tedium (+5e-2) | Done |
| Other non-sleep actions accumulate tedium at idle rate (+2e-2) | Done |
| PAIN + TEDIUM as live dims in `model_contract.json` | Done |
| All object types in `simulations/basic.conf` | Done |

## Proposed changes

### 1. Tedium accumulation during SLEEP — `FullAppraisal.java`

**Current** (`FullAppraisal.dispatchTediumStimulus`, line 204):
```java
private void dispatchTediumStimulus(ActionType selectedAction) {
    if (selectedAction == ActionType.SLEEP) return;   // <-- excluded
    ...
}
```

**Proposed**: remove the early return so SLEEP falls through to the `else` branch,
accumulating tedium at `TEDIUM_IDLE_RATE` (2e-2) per cognitive cycle.

No new constant is needed — the idle rate is the right magnitude for a passive, unconscious
accumulation.  `HomeostaticRegulation.handleTedium` already skips `EvaluationStimulus` for
any action other than WANDER/OBSERVE, so no learning signal is emitted for SLEEP tedium;
it accumulates silently as background pressure.

### 2. Fix stale comments in `EmotionalSystemActor.java`

PAIN (index 4) and TEDIUM (index 5) are listed as `// placeholder` but are now actively
regulated.  Update comments to `// live`.

## Files changed

| File | Change |
|------|--------|
| `creature/components/FullAppraisal.java` | Remove `if (SLEEP) return` guard in `dispatchTediumStimulus` |
| `creature/components/EmotionalSystemActor.java` | Update comments for PAIN and TEDIUM |

## Acceptance criteria

- `mvn package` compiles clean.
- In a running simulation, creatures in SLEEP state produce `TediumStimulus(delta=+0.02)` in
  the DB records each cognitive cycle.
- After waking, tedium level is higher than at sleep onset.
- No regression: WANDER still reduces tedium; EAT/APPROACH/etc. still accumulate at idle rate.
