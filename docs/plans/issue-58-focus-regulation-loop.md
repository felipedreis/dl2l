# Issue #58 — Close the Attention/Focus Regulation Loop

**Closes:** Finding D from `docs/roadmap/Campos2015_Model_Parity.md`

## Problem

`FullAppraisal.produceCortical` computes focus purely from arousal:
```java
focus = max(MAX_VISION_FIELD_OPENING * behaviouralEfficiency, MIN_VISION_FIELD_OPENING)
```

Two gaps:
1. **APPROACH/EAT**: focus should narrow as distance shrinks → "locked on target" at contact.  
   The attentional feedback loop (approach → narrower field → fewer distractors → stable pursuit) is absent.
2. **SLEEP**: focus stays at the efficiency-scaled value; `Eye` keeps emitting `VisualStimulus`.

## Approach

### 1. `FullAppraisal.produceCortical` — focus per action

| Action | Focus formula |
|---|---|
| `APPROACH` | `MIN + (MAX − MIN) · clamp(distance / VISION_RADIUS, 0, 1)` — decreasing as creature nears target |
| `EAT` | `MIN` (interpolation at distance=0; locked on target) |
| `SLEEP` | `MIN` (eye closed) |
| `WANDER`, `OBSERVE`, `AVOID`, `ESCAPE` | unchanged default: `max(MAX · efficiency, MIN)` |

Constants needed: `MIN_VISION_FIELD_OPENING=50`, `MAX_VISION_FIELD_OPENING=150`, `DEFAULT_VISION_FIELD_RADIUS=150`.  
No new message types or constants required.

### 2. `Eye.onReceive` — gate on closed field

Before emitting `VisualStimulus` when handling a `LuminousStimulus`, add:
```java
if (creature.getVisionFieldOpening() <= Constants.MIN_VISION_FIELD_OPENING) {
    continue; // field is at minimum (SLEEP or contact); suppress VisualStimulus + ObjectSeenState
}
```

The gate fires when:
- `SLEEP` is active (focus set to MIN)
- `EAT` at contact (focus = MIN from interpolation)
- Arousal at floor (0.18) during WANDER — biologically plausible (barely conscious)

### 3. `TestingCreature.init` — initial eye state

Change initial `visionFieldOpening` from `MIN` to `MAX` so tests that inject `LuminousStimulus`
before the first cognitive cycle see an open eye. Without this fix, adding the gate would break
existing tests that call `injectLuminous` before `tick()`.

## Files to change

| File | Change |
|---|---|
| `FullAppraisal.java` | switch cases for APPROACH (interpolate), EAT (MIN), SLEEP (MIN) |
| `Eye.java` | gate LuminousStimulus handling on `getVisionFieldOpening() <= MIN` |
| `TestingCreature.java` | initial `visionFieldOpening = MAX_VISION_FIELD_OPENING` |
| `FocusRegulationTest.java` (new) | unit tests per acceptance criteria |

## Test plan

All tests use `TestingHarness` with deterministic action selection:

- **approach_focus_decreases_with_distance**: Hungry creature + tendency on; inject fruit at D=120
  and D=30 separately; assert focus(120)=130 > focus(30)=70 (exact interpolated values).
  APPROACH is deterministic: tendency filter leaves {APPROACH, WANDER}, AFFORDANCE selects
  APPROACH (p=25 vs WANDER p=0 in initial operant table).

- **eat_focus_is_minimal**: Hungry creature + tendency on; fruit at distance=0 (contact);
  `actionsAtContact` → tendency leaves {EAT, WANDER} → AFFORDANCE selects EAT deterministically.
  Assert `CorticalStimulus.focus == MIN_VISION_FIELD_OPENING`.

- **sleep_focus_is_minimal**: Sleepy creature; run 50 ticks; collect all SLEEP CorticalStimuli;
  assert every one has `focus == MIN_VISION_FIELD_OPENING`. Anti-micro-nap hysteresis guarantees
  ≥10 consecutive SLEEP cycles once sleep starts, so the 50-tick window is sufficient.

- **eye_gated_when_field_closed**: Set `creature.setVisionFieldOpening(MIN)` directly; inject
  `LuminousStimulus`; verify `sensoryCortexRecorder()` has no `VisualStimulus`. Tests Eye gating
  independently of action selection.

- **wander_observe_keep_wide_focus**: Default creature with arousal > MIN_AROUSAL_LEVEL; verify
  CorticalStimulus.focus > MIN when WANDER or OBSERVE is chosen.

## Acceptance criteria (from issue)

- [ ] Focus decreases monotonically as APPROACH distance shrinks toward zero.
- [ ] During a SLEEP episode: `focus == MIN_VISION_FIELD_OPENING` and `ObjectSeenState` count == 0.
- [ ] WANDER / OBSERVE / self-perception retain arousal-scaled wide field (no regression).
- [ ] No feeding regression on a smoke run (creature still feeds and survives).
- [ ] `mvn package` clean.
